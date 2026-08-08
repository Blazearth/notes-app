import { BlurView } from 'expo-blur';
import React, { useState } from 'react';
import { Platform, Pressable, View, type LayoutChangeEvent } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useHaptic } from '@/motion/haptics';
import { activeTabFraction } from '@/motion/tabs';
import { withAlpha } from '@/theme/contrast';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

/** Inner padding of the floating pill; the travelling fill inherits it. */
const STRIP_PAD = 6;

export interface NavItem {
  key: string;
  label: string;
}

export interface BottomNavProps {
  items: NavItem[];
  activeKey: string;
  onSelect: (key: string) => void;
  onCapture: () => void;
}

/**
 * One tab.
 *
 * Split out so the dot can hold its own animation: hooks cannot live in a
 * `.map` callback, and the alternative — one shared value per tab in the
 * parent — makes adding a tab a two-place change.
 */
function NavTab({
  item,
  tabIndex,
  active,
  floating,
  onPress,
}: {
  item: NavItem;
  tabIndex: number;
  active: boolean;
  floating: boolean;
  onPress: () => void;
}) {
  const { palette, spacing } = useTheme();
  const reduced = useReducedMotion();

  /**
   * Dot scale driven directly by activeTabFraction on the UI thread.
   * No useEffect, no re-render — the animation starts the same frame the
   * shared value changes, whether from a tap or a swipe.
   *
   * Bonus: the dot pulses proportionally during a swipe, giving a live
   * position indicator that mirrors the fill pill.
   */
  const dotStyle = useAnimatedStyle(() => {
    if (reduced) return { transform: [{ scale: active ? 1.4 : 1 }] };
    const distance = Math.abs(activeTabFraction.value - tabIndex);
    const scale = 1 + Math.max(0, 1 - distance) * 0.4;
    return { transform: [{ scale }] };
  });

  const dotColor = active
    ? floating
      ? palette.navActiveText
      : palette.accent
    : floating
      ? palette.navInactiveText
      : palette.textFaint;

  const labelColor = active
    ? floating
      ? palette.navActiveText
      : palette.text
    : floating
      ? palette.navInactiveText
      : palette.textFaint;

  return (
    <Pressable
      accessibilityRole="tab"
      accessibilityState={{ selected: active }}
      accessibilityLabel={item.label}
      onPress={onPress}
      style={{
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: spacing.sm - 2,
        paddingVertical: 9,
        paddingHorizontal: spacing.lg - 2,
      }}
    >
      <Animated.View
        style={[
          { width: 8, height: 8, borderRadius: 4, backgroundColor: dotColor },
          dotStyle,
        ]}
      />
      <AppText
        variant={active ? 'navLabel' : 'bodySmall'}
        style={{ fontSize: 11.5, color: labelColor }}
      >
        {item.label}
      </AppText>
    </Pressable>
  );
}

/**
 * Home / Library / Spaces plus the single Capture action.
 *
 * Two shapes, chosen by the `navBarStyle` preference (PennyWise's
 * `NavBarStyle`): `floating` is the designed inverse-ink pill inset from the
 * screen edges; `normal` is a docked bar for people who dislike content
 * scrolling under a floating element.
 *
 * In `floating`, the selected fill is one element that slides between tabs.
 * Painting the background on whichever tab happens to be active is the cheaper
 * implementation and the worse interaction — it cuts, where this pans, and the
 * pan is what tells you Library sits between Home and Spaces.
 */
export function BottomNav({ items, activeKey, onSelect, onCapture }: BottomNavProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const haptic = useHaptic();
  const reduced = useReducedMotion();
  const { palette, radius, spacing, layout, elevation, navBarStyle, blurEffects } = theme;

  const floating = navBarStyle === 'floating';

  const [width, setWidth] = useState(0);

  /**
   * Strip width as a shared value so the fill pill position can be computed
   * entirely on the UI thread: fillX = activeTabFraction * stripSV.
   * Set once on first layout; stable thereafter.
   */
  const stripSV = useSharedValue(0);
  const strip = width > 0 ? width / items.length : 0;

  const onLayout = (event: LayoutChangeEvent) => {
    const w = event.nativeEvent.layout.width;
    setWidth(w);
    stripSV.value = w / items.length;
  };

  /**
   * Fill position driven directly by activeTabFraction on the UI thread.
   *
   * Previously this was a useEffect on activeIndex → withSpring(target) which
   * meant every tap had to survive a full React render cycle before the
   * animation even started. Now the shared value is written by selectTab /
   * the swipe gesture and read here — the animation starts the same frame.
   */
  const fillStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: activeTabFraction.value * stripSV.value }],
  }));

  const select = (key: string) => {
    if (key === activeKey) return;
    haptic('selection');
    onSelect(key);
  };

  const fab = (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel="Capture"
      onPress={onCapture}
      weight="tile"
      // Opening capture is the app's one primary action — it gets the heavier
      // tap that separates "I meant this" from browsing.
      haptic="medium"
      style={{
        width: layout.fabSize,
        height: layout.fabSize,
        borderRadius: layout.fabSize / 2,
        backgroundColor: palette.accent,
        alignItems: 'center',
        justifyContent: 'center',
        ...elevation.fab,
        shadowColor: palette.accent,
      }}
    >
      <Glyph name="plus" size={16} weight={2} color={palette.onAccent} />
    </Touchable>
  );

  const tabs = items.map((item, index) => (
    <NavTab
      key={item.key}
      item={item}
      tabIndex={index}
      active={item.key === activeKey}
      floating={floating}
      onPress={() => select(item.key)}
    />
  ));


  /**
   * The tabs and the fill share one unpadded track.
   *
   * The fill used to be an absolute child of the padded bar with
   * `top/left/bottom: 0`, on the assumption those insets resolve against the
   * parent's *padding* box. They do not resolve there consistently — the fill
   * reached the bar's outer edge and swallowed the 6px of dark surround that
   * frames the selected tab. Nesting an unpadded track means padding box and
   * border box are the same rectangle, so the insets have only one possible
   * meaning, and `strip` is a plain division of the measured width.
   */
  const track = (
    <View
      onLayout={onLayout}
      style={{ flex: 1, flexDirection: 'row', alignItems: 'center' }}
    >
      {strip > 0 ? (
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              left: 0,
              top: 0,
              bottom: 0,
              width: strip,
              borderRadius: radius.pill,
              backgroundColor: palette.navActiveBg,
            },
            fillStyle,
          ]}
        />
      ) : null}
      {tabs}
    </View>
  );

  if (floating) {
    const pillFill = withAlpha(palette.navBg, theme.alpha.navBar);
    return (
      <View
        pointerEvents="box-none"
        style={{
          position: 'absolute',
          left: layout.navInset,
          right: layout.navInset,
          bottom: layout.navInset + insets.bottom * 0.5,
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
        }}
      >
        <View style={{ flex: 1, borderRadius: radius.pill, overflow: 'hidden', ...elevation.nav }}>
          {blurEffects ? (
            <BlurView
              intensity={40}
              // The pill is inverse-ink in both themes, so the blur tint does
              // not flip with the colour scheme.
              tint="dark"
              // Android needs the opt-in method for a real blur; without it the
              // view falls back to a flat translucent fill.
              experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                padding: STRIP_PAD,
                backgroundColor: withAlpha(palette.navBg, 0.72),
              }}
            >
              {track}
            </BlurView>
          ) : (
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                padding: STRIP_PAD,
                backgroundColor: pillFill,
              }}
            >
              {track}
            </View>
          )}
        </View>
        {fab}
      </View>
    );
  }

  return (
    <View pointerEvents="box-none" style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}>
      <View
        pointerEvents="box-none"
        style={{ alignItems: 'flex-end', paddingRight: layout.navInset, marginBottom: spacing.md }}
      >
        {fab}
      </View>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingTop: spacing.sm,
          paddingBottom: spacing.sm + insets.bottom,
          paddingHorizontal: spacing.sm,
          backgroundColor: palette.surface,
          borderTopWidth: 1,
          borderTopColor: palette.border,
        }}
      >
        {tabs}
      </View>
    </View>
  );
}
