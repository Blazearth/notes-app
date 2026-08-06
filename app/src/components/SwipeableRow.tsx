import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';

import { useHaptic } from '@/motion/haptics';
import { Spring } from '@/theme/motion';
import { useTheme } from '@/theme/ThemeProvider';
import { Glyph } from './Glyph';

const ACTION_WIDTH = 76;
const TRIGGER_DISTANCE = 68;

export interface SwipeableRowProps {
  children: React.ReactNode;
  /** Swipe right. Omit to disable that direction entirely. */
  onFavorite?: () => void;
  /** Swipe left. Omit to disable that direction entirely. */
  onArchive?: () => void;
  /** True in multi-select mode — swiping is suspended so a drag can't fire an action mid-selection. */
  disabled?: boolean;
}

/**
 * Reveals a favorite action on a right swipe and an archive action on a left
 * swipe, snapping back once the gesture ends. Only two actions, not the full
 * "delete / add to space / share" set from the design wishlist — there is no
 * delete endpoint (Postgres has no undo, and nothing here should destroy data
 * without a confirmation step this pass didn't build), and Space/share need
 * their own picker UI. Favorite and archive are both single-tap-safe
 * reversible flag flips, which is what makes a swipe-to-fire gesture safe
 * without a confirmation dialog in between.
 */
export function SwipeableRow({ children, onFavorite, onArchive, disabled }: SwipeableRowProps) {
  const { palette, radius, spacing } = useTheme();
  const fireHaptic = useHaptic();
  const translateX = useSharedValue(0);
  const reduced = useReducedMotion();

  const canFavorite = !!onFavorite;
  const canArchive = !!onArchive;

  const pan = Gesture.Pan()
    .enabled(!disabled && (canFavorite || canArchive))
    .activeOffsetX([-12, 12])
    .failOffsetY([-10, 10])
    .onUpdate((e) => {
      'worklet';
      const min = canArchive ? -ACTION_WIDTH - 24 : 0;
      const max = canFavorite ? ACTION_WIDTH + 24 : 0;
      translateX.value = Math.min(max, Math.max(min, e.translationX));
    })
    .onEnd(() => {
      'worklet';
      if (translateX.value > TRIGGER_DISTANCE && onFavorite) {
        translateX.value = withSpring(0, Spring.press);
        runOnJS(fireHaptic)('success');
        runOnJS(onFavorite)();
      } else if (translateX.value < -TRIGGER_DISTANCE && onArchive) {
        translateX.value = withSpring(0, Spring.press);
        runOnJS(fireHaptic)('success');
        runOnJS(onArchive)();
      } else {
        translateX.value = withSpring(0, Spring.press);
      }
    });

  const rowStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: reduced ? 0 : translateX.value }],
  }));

  const favoriteStyle = useAnimatedStyle(() => ({
    opacity: Math.min(1, translateX.value / TRIGGER_DISTANCE),
  }));

  const archiveStyle = useAnimatedStyle(() => ({
    opacity: Math.min(1, -translateX.value / TRIGGER_DISTANCE),
  }));

  if (!canFavorite && !canArchive) return <>{children}</>;

  return (
    <View style={{ position: 'relative' }}>
      {canFavorite ? (
        <Animated.View
          pointerEvents="none"
          style={[
            StyleSheet.absoluteFill,
            favoriteStyle,
            {
              borderRadius: radius.md,
              backgroundColor: palette.accent,
              alignItems: 'flex-start',
              justifyContent: 'center',
              paddingLeft: spacing.lg,
            },
          ]}
        >
          <Glyph name="heart" size={20} weight={2} color={palette.background} />
        </Animated.View>
      ) : null}
      {canArchive ? (
        <Animated.View
          pointerEvents="none"
          style={[
            StyleSheet.absoluteFill,
            archiveStyle,
            {
              borderRadius: radius.md,
              backgroundColor: palette.warning,
              alignItems: 'flex-end',
              justifyContent: 'center',
              paddingRight: spacing.lg,
            },
          ]}
        >
          <Glyph name="archive" size={20} weight={2} color={palette.background} />
        </Animated.View>
      ) : null}
      <GestureDetector gesture={pan}>
        <Animated.View style={rowStyle}>{children}</Animated.View>
      </GestureDetector>
    </View>
  );
}
