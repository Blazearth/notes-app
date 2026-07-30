import { BlurView } from 'expo-blur';
import React, { useEffect, useRef, useState } from 'react';
import { Platform, Pressable, View, type LayoutChangeEvent } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useHaptic } from '@/motion/haptics';
import { withAlpha } from '@/theme/contrast';
import { Spring } from '@/theme/motion';
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
  active,
  floating,
  onPress,
}: {
  item: NavItem;
  active: boolean;
  floating: boolean;
  onPress: () => void;
}) {
  const { palette, spacing } = useTheme();
  const reduced = useReducedMotion();
  const t = useSharedValue(active ? 1 : 0);

  useEffect(() => {
    const target = active ? 1 : 0;
    t.value = reduced ? target : withSpring(target, Spring.travel);
  }, [active, reduced, t]);

  // The dot swells rather than only recolouring, so the active tab is legible
  // as a shape — colour alone fails for the ~8% of men with a colour vision
  // deficiency, and fails again against the blurred content behind the pill.
  const dotStyle = useAnimatedStyle(() => ({ transform: [{ scale: 1 + t.value * 0.4 }] }));

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
      // The travelling fill is the press feedback here; scaling the tab as
      // well would compete with it.
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
  const activeIndex = Math.max(
    0,
    items.findIndex((item) => item.key === activeKey),
  );

  // `width` is measured on the inner track, which carries no padding of its
  // own — so this is a plain division rather than the strip width minus the
  // container's inset. See `track` below for why that matters.
  const strip = width > 0 ? width / items.length : 0;
  const x = useSharedValue(0);
  const settled = useRef(false);

  useEffect(() => {
    if (strip <= 0) return;
    const target = activeIndex * strip;
    // Placed, not animated, on first measure — otherwise the fill sweeps in
    // from Home every time the shell mounts.
    if (settled.current && !reduced) {
      x.value = withSpring(target, Spring.travel);
    } else {
      x.value = target;
      settled.current = true;
    }
  }, [activeIndex, strip, reduced, x]);

  const fillStyle = useAnimatedStyle(() => ({ transform: [{ translateX: x.value }] }));

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

  const tabs = items.map((item) => (
    <NavTab
      key={item.key}
      item={item}
      active={item.key === activeKey}
      floating={floating}
      onPress={() => select(item.key)}
    />
  ));

  const onLayout = (event: LayoutChangeEvent) => setWidth(event.nativeEvent.layout.width);

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
