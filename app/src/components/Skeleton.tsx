import React, { useEffect } from 'react';
import type { DimensionValue } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withSequence,
  withTiming,
} from 'react-native-reanimated';

import { useTheme } from '@/theme/ThemeProvider';

/** A pulsing placeholder block for content that hasn't arrived yet. */
export function Skeleton({
  width,
  height,
  radius: cornerRadius,
}: {
  width: DimensionValue;
  height: number;
  radius?: number;
}) {
  const { palette, radius } = useTheme();
  const reduced = useReducedMotion();
  const pulse = useSharedValue(reduced ? 0.55 : 0.35);

  useEffect(() => {
    if (reduced) return;
    pulse.value = withRepeat(
      withSequence(withTiming(0.7, { duration: 700 }), withTiming(0.35, { duration: 700 })),
      -1,
      true,
    );
  }, [reduced, pulse]);

  const style = useAnimatedStyle(() => ({ opacity: pulse.value }));

  return (
    <Animated.View
      style={[
        { width, height, borderRadius: cornerRadius ?? radius.sm, backgroundColor: palette.surfaceVariant },
        style,
      ]}
    />
  );
}
