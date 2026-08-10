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
const ARCHIVE_TRIGGER = 68;
const DELETE_TRIGGER = 160; // swipe further left for delete

export interface SwipeableRowProps {
  children: React.ReactNode;
  onFavorite?: () => void;
  onArchive?: () => void;
  onDelete?: () => void;
  disabled?: boolean;
}

export function SwipeableRow({ children, onFavorite, onArchive, onDelete, disabled }: SwipeableRowProps) {
  const { palette, radius, spacing } = useTheme();
  const fireHaptic = useHaptic();
  const translateX = useSharedValue(0);
  const reduced = useReducedMotion();

  const canFavorite = !!onFavorite;
  const canLeft = !!(onArchive || onDelete);

  const maxLeft = canLeft ? -(onDelete ? DELETE_TRIGGER + 24 : ACTION_WIDTH + 24) : 0;

  const pan = Gesture.Pan()
    .enabled(!disabled && (canFavorite || canLeft))
    .activeOffsetX([-12, 12])
    .failOffsetY([-10, 10])
    .onUpdate((e) => {
      'worklet';
      const min = maxLeft;
      const max = canFavorite ? ACTION_WIDTH + 24 : 0;
      translateX.value = Math.min(max, Math.max(min, e.translationX));
    })
    .onEnd(() => {
      'worklet';
      if (translateX.value > ARCHIVE_TRIGGER && onFavorite) {
        translateX.value = withSpring(0, Spring.press);
        runOnJS(fireHaptic)('success');
        runOnJS(onFavorite)();
      } else if (translateX.value < -DELETE_TRIGGER && onDelete) {
        translateX.value = withSpring(0, Spring.press);
        runOnJS(fireHaptic)('error');
        runOnJS(onDelete)();
      } else if (translateX.value < -ARCHIVE_TRIGGER && onArchive) {
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

  const favoriteOpacity = useAnimatedStyle(() => ({
    opacity: Math.min(1, translateX.value / ARCHIVE_TRIGGER),
  }));

  // Left background: red when past delete threshold, yellow otherwise
  const leftBg = useAnimatedStyle(() => {
    const past = translateX.value < -DELETE_TRIGGER;
    return { backgroundColor: past ? palette.danger : palette.warning };
  });

  const archiveOpacity = useAnimatedStyle(() => ({
    opacity: Math.min(1, -translateX.value / ARCHIVE_TRIGGER),
  }));

  if (!canFavorite && !canLeft) return <>{children}</>;

  return (
    <View style={{ position: 'relative' }}>
      {canFavorite ? (
        <Animated.View
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, favoriteOpacity, {
            borderRadius: radius.md,
            backgroundColor: palette.accent,
            alignItems: 'flex-start',
            justifyContent: 'center',
            paddingLeft: spacing.lg,
          }]}
        >
          <Glyph name="heart" size={20} weight={2} color={palette.background} />
        </Animated.View>
      ) : null}
      {canLeft ? (
        <Animated.View
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, archiveOpacity, leftBg, {
            borderRadius: radius.md,
            alignItems: 'flex-end',
            justifyContent: 'center',
            paddingRight: spacing.lg,
            gap: spacing.sm,
          }]}
        >
          <Glyph
            name={onDelete ? 'close' : 'archive'}
            size={20} weight={2}
            color={palette.background}
          />
        </Animated.View>
      ) : null}
      <GestureDetector gesture={pan}>
        <Animated.View style={rowStyle}>{children}</Animated.View>
      </GestureDetector>
    </View>
  );
}
