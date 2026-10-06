import { BlurView } from 'expo-blur';
import React, { useCallback, useEffect, useRef } from 'react';
import { Modal, Platform, Pressable, View } from 'react-native';
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
import { Touchable } from './Touchable';

const AnimatedBlurView = Animated.createAnimatedComponent(BlurView);

const BACKDROP_BLUR_INTENSITY = 20;
const BACKDROP_SCRIM_OPACITY = 0.16;
/** Larger than any real sheet height, so the panel always starts fully off-screen. */
const PANEL_ENTER_OFFSET = 420;
/** Fixed, like `Sheet`'s dismissal — a spring's settle tail otherwise makes the
 * `visible` flip land well after the sheet looks gone. */
const DISMISS_MS = 160;

export interface ConfirmSheetProps {
  visible: boolean;
  title: string;
  /**
   * The thing this action targets, surfaced verbatim (a save's title, "12
   * saves") rather than folded into the message — metadata is part of the
   * product experience here, not a detail to summarize away.
   */
  itemLabel?: string;
  message: string;
  confirmLabel: string;
  /** Subtle destructive treatment (tinted danger, not a solid red button) — a delete confirmation should read as serious, not alarming. */
  destructive?: boolean;
  onConfirm: () => void;
  /**
   * Omit for a single-button info sheet (a "Got it" dismissal, no real
   * choice to make) — the Cancel row is hidden and the backdrop/back-button
   * dismiss through `onConfirm` instead.
   */
  onCancel?: () => void;
}

/**
 * Bottom-sheet confirmation for destructive or important actions — same
 * chrome and motion language as `Sheet`, but driven by `visible`/`onCancel`
 * props instead of the router, since this fires ad hoc from a list row
 * rather than from navigation.
 *
 * Replaces `Alert.alert` for these cases: the OS alert renders as bare
 * platform chrome (a gray rectangle, teal buttons) that breaks Weavr's visual
 * language at exactly the moment — a destructive confirmation — where
 * looking trustworthy matters most.
 */
export function ConfirmSheet({
  visible,
  title,
  itemLabel,
  message,
  confirmLabel,
  destructive = true,
  onConfirm,
  onCancel,
}: ConfirmSheetProps) {
  const { palette, radius, spacing, elevation, alpha, blurEffects } = useTheme();
  const insets = useSafeAreaInsets();
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(0);

  /**
   * The first choice wins. Without this, a backdrop tap (or Android back)
   * inside the 160ms exit replaced the Confirm animation, its callback ran with
   * `finished = false`, and only `onCancel` fired — the confirmed delete never
   * happened. Same guard as `Sheet`'s `dismissing`.
   */
  const closing = useRef(false);

  useEffect(() => {
    if (visible) closing.current = false;
    if (visible) progress.value = reducedMotion ? 1 : withSpring(1, Spring.enter);
    // Entrance only — dismissal is driven by the button handlers below, not by `visible` flipping false.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  const closeThen = useCallback(
    (action: () => void) => {
      if (closing.current) return;
      closing.current = true;
      if (reducedMotion) {
        action();
        return;
      }
      progress.value = withTiming(0, { duration: DISMISS_MS }, (finished) => {
        if (finished) runOnJS(action)();
      });
    },
    [reducedMotion, progress],
  );

  const dismiss = onCancel ?? onConfirm;
  const handleDismiss = useCallback(() => closeThen(dismiss), [closeThen, dismiss]);
  const handleConfirm = useCallback(() => closeThen(onConfirm), [closeThen, onConfirm]);

  const blurProps = useAnimatedProps(() => ({ intensity: progress.value * BACKDROP_BLUR_INTENSITY }));
  const scrimStyle = useAnimatedStyle(() => ({ opacity: progress.value * BACKDROP_SCRIM_OPACITY }));
  const fallbackDimStyle = useAnimatedStyle(() => ({ opacity: progress.value * (alpha.scrim + 0.3) }));
  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: interpolate(progress.value, [0, 1], [PANEL_ENTER_OFFSET, 0]) }],
    opacity: interpolate(progress.value, [0, 0.35, 1], [0, 1, 1]),
  }));

  return (
    <Modal visible={visible} transparent animationType="none" statusBarTranslucent onRequestClose={handleDismiss}>
      <View style={{ flex: 1, justifyContent: 'flex-end' }}>
        <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}>
          <Pressable accessibilityRole="button" accessibilityLabel="Dismiss" onPress={handleDismiss} style={{ flex: 1 }}>
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
              paddingHorizontal: spacing.xl,
              paddingBottom: spacing.xl + insets.bottom,
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
              marginBottom: spacing.lg,
            }}
          />

          <AppText variant="heading" style={{ textAlign: 'center' }}>
            {title}
          </AppText>

          {itemLabel ? (
            <View
              style={{
                backgroundColor: palette.surfaceVariant,
                borderRadius: radius.sm,
                paddingVertical: spacing.smd,
                paddingHorizontal: spacing.md,
                marginTop: spacing.lg,
              }}
            >
              <AppText variant="label" numberOfLines={2} style={{ textAlign: 'center' }}>
                {itemLabel}
              </AppText>
            </View>
          ) : null}

          <AppText variant="caption" tone="muted" style={{ textAlign: 'center', marginTop: spacing.md, marginBottom: spacing.xl }}>
            {message}
          </AppText>

          <Touchable
            accessibilityRole="button"
            accessibilityLabel={confirmLabel}
            onPress={handleConfirm}
            haptic={destructive ? 'medium' : 'light'}
            weight="tile"
            style={{
              alignItems: 'center',
              paddingVertical: spacing.md,
              borderRadius: radius.pill,
              backgroundColor: destructive ? `${palette.danger}26` : palette.accent,
              marginBottom: spacing.smd,
            }}
          >
            <AppText
              variant="label"
              style={{ fontSize: 14, fontWeight: '600', color: destructive ? palette.danger : palette.onAccent }}
            >
              {confirmLabel}
            </AppText>
          </Touchable>

          {onCancel ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Cancel"
              onPress={handleDismiss}
              haptic="selection"
              style={{ alignItems: 'center', paddingVertical: spacing.smd }}
            >
              <AppText variant="label" tone="muted" style={{ fontSize: 14 }}>
                Cancel
              </AppText>
            </Touchable>
          ) : null}
        </Animated.View>
      </View>
    </Modal>
  );
}
