import React, { useState } from 'react';
import { StyleSheet, View, type LayoutChangeEvent, type ViewStyle } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

export interface HatchThumbProps {
  /** Monospace stand-in label, e.g. "recipe photo". Omit for small tiles. */
  label?: string;
  width?: number | `${number}%`;
  height: number;
  radius?: number;
  /** Stripe period; the mockups use 16 on covers and 12 on 40px tiles. */
  period?: number;
  style?: ViewStyle;
}

/**
 * Placeholder thumbnail — the mockups' diagonal hatch, standing in for imagery
 * that only exists once a save has been processed server-side.
 *
 * CSS `repeating-linear-gradient` has no RN equivalent, so the stripes are real
 * views inside a rotated, clipped container. The sheet is sized from the
 * measured box (a 45° rotation needs a square of side ≈ 0.71·(w+h) to cover it)
 * rather than a generous constant — at 40px that is five stripes instead of
 * thirty, which matters when a list renders a dozen of them.
 */
export function HatchThumb({ label, width = '100%', height, radius, period = 16, style }: HatchThumbProps) {
  const { palette, radius: r, type } = useTheme();
  const [box, setBox] = useState({ width: 0, height: 0 });

  const onLayout = (event: LayoutChangeEvent) => {
    const { width: w, height: h } = event.nativeEvent.layout;
    if (w !== box.width || h !== box.height) setBox({ width: w, height: h });
  };

  const span = Math.ceil((box.width + box.height) * 0.72);
  const stripe = period / 2;
  const count = span > 0 ? Math.ceil(span / period) + 1 : 0;

  return (
    <View
      onLayout={onLayout}
      style={[
        {
          width,
          height,
          borderRadius: radius ?? r.sm,
          backgroundColor: palette.hatchA,
          overflow: 'hidden',
          alignItems: 'center',
          justifyContent: 'center',
        },
        style,
      ]}
    >
      <View
        pointerEvents="none"
        style={[StyleSheet.absoluteFill, { alignItems: 'center', justifyContent: 'center' }]}
      >
        <View style={{ width: span, height: span, transform: [{ rotate: '45deg' }] }}>
          {Array.from({ length: count }).map((_, i) => (
            <View
              key={i}
              style={{
                position: 'absolute',
                top: 0,
                left: i * period,
                width: stripe,
                height: span,
                backgroundColor: palette.hatchB,
              }}
            />
          ))}
        </View>
      </View>
      {label ? (
        <AppText tone="muted" style={type.mono}>
          {label}
        </AppText>
      ) : null}
    </View>
  );
}
