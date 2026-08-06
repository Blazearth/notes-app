import { BlurView } from 'expo-blur';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef } from 'react';
import { Platform, Pressable, View } from 'react-native';
import Animated, {
  interpolate,
  runOnJS,
  useAnimatedProps,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Spring } from '@/theme/motion';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

const AnimatedBlurView = Animated.createAnimatedComponent(BlurView);

const BACKDROP_BLUR_INTENSITY = 20;
const BACKDROP_SCRIM_OPACITY = 0.16;
/** Larger than any real sheet height, so the panel always starts fully off-screen. */
const PANEL_ENTER_OFFSET = 420;
/** Fixed, like `CaptureSheet`'s dismissal — a spring's settle tail otherwise makes `router.back()` land well after the sheet looks gone. */
const DISMISS_MS = 160;

export interface SheetRenderProps {
  dismiss: () => void;
}

export interface SheetProps {
  title: string;
  subtitle?: string;
  children: (props: SheetRenderProps) => React.ReactNode;
}

/**
 * Shared chrome for the two Spaces sheets (Create, Join) — same rounded
 * surface, grabber, blurred backdrop and spring/timing pair as `CaptureSheet`,
 * so a sheet feels like the same object everywhere it appears in the app.
 *
 * Not merged with `CaptureSheet` itself: that one's panel grows out of the
 * fixed-position FAB (`transformOrigin` pinned to the button's measured
 * centre) and choreographs its tiles separately on the way out. These sheets
 * open from a header button that scrolls with the screen, so a plain
 * slide-up/fade is the honest animation rather than a copy of a trick that
 * does not apply here.
 */
export function Sheet({ title, subtitle, children }: SheetProps) {
  const { palette, radius, spacing, layout, elevation, alpha, blurEffects } = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(0);
  const dismissing = useRef(false);

  useEffect(() => {
    progress.value = reducedMotion ? 1 : withSpring(1, Spring.enter);
    // Mount-only entrance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const dismiss = useCallback(() => {
    if (dismissing.current) return;
    dismissing.current = true;

    if (reducedMotion) {
      router.back();
      return;
    }

    progress.value = withTiming(0, { duration: DISMISS_MS }, (finished) => {
      if (finished) runOnJS(router.back)();
    });
  }, [reducedMotion, router, progress]);

  const blurProps = useAnimatedProps(() => ({ intensity: progress.value * BACKDROP_BLUR_INTENSITY }));
  const scrimStyle = useAnimatedStyle(() => ({ opacity: progress.value * BACKDROP_SCRIM_OPACITY }));
  const fallbackDimStyle = useAnimatedStyle(() => ({ opacity: progress.value * (alpha.scrim + 0.3) }));
  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: interpolate(progress.value, [0, 1], [PANEL_ENTER_OFFSET, 0]) }],
    opacity: interpolate(progress.value, [0, 0.35, 1], [0, 1, 1]),
  }));

  return (
    <View style={{ flex: 1, justifyContent: 'flex-end' }}>
      <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="Dismiss" onPress={dismiss} style={{ flex: 1 }}>
          {blurEffects ? (
            <View style={{ flex: 1 }}>
              <AnimatedBlurView
                animatedProps={blurProps}
                tint={palette.isDark ? 'dark' : 'light'}
                experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
                style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
              />
              <Animated.View
                style={[
                  { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: '#000000' },
                  scrimStyle,
                ]}
              />
            </View>
          ) : (
            <Animated.View style={[{ flex: 1, backgroundColor: palette.scrim }, fallbackDimStyle]} />
          )}
        </Pressable>
      </View>

      <Animated.View
        style={[
          {
            backgroundColor: palette.surface,
            borderTopLeftRadius: radius.xl,
            borderTopRightRadius: radius.xl,
            paddingTop: spacing.md,
            paddingHorizontal: layout.screenGutter,
            paddingBottom: spacing.xxl + insets.bottom,
            ...elevation.sheet,
          },
          panelStyle,
        ]}
      >
        <View
          style={{
            width: 36,
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.border,
            alignSelf: 'center',
            marginBottom: spacing.lg + 2,
          }}
        />

        <View
          style={{
            flexDirection: 'row',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            marginBottom: spacing.xl,
          }}
        >
          <View style={{ flex: 1, paddingRight: spacing.md }}>
            <AppText variant="heading">{title}</AppText>
            {subtitle ? (
              <AppText tone="muted" style={{ fontSize: 12.5, marginTop: spacing.xs }}>
                {subtitle}
              </AppText>
            ) : null}
          </View>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Close"
            onPress={dismiss}
            haptic="selection"
            weight="tile"
            style={{
              width: 32,
              height: 32,
              borderRadius: radius.pill,
              backgroundColor: palette.surfaceVariant,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name="close" size={14} weight={2} />
          </Touchable>
        </View>

        {children({ dismiss })}
      </Animated.View>
    </View>
  );
}
