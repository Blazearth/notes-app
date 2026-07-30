import React from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';

import type { HapticTone } from '@/motion/haptics';
import { useTheme } from '@/theme/ThemeProvider';
import { Touchable } from './Touchable';

export interface CardProps {
  children?: React.ReactNode;
  /** `accent` swaps the surface for the accent container (the digest card). */
  variant?: 'surface' | 'accent' | 'plain';
  padding?: number;
  radius?: number;
  onPress?: () => void;
  haptic?: HapticTone | null;
  style?: StyleProp<ViewStyle>;
}

export function Card({
  children,
  variant = 'surface',
  padding,
  radius,
  onPress,
  haptic = 'light',
  style,
}: CardProps) {
  const { palette, radius: r, layout, elevation } = useTheme();

  const surface: ViewStyle =
    variant === 'accent'
      ? { backgroundColor: palette.accentContainer }
      : variant === 'plain'
        ? {}
        : { backgroundColor: palette.surface, borderWidth: 1, borderColor: palette.border };

  const base: StyleProp<ViewStyle> = [
    {
      borderRadius: radius ?? r.lg,
      padding: padding ?? layout.cardPadding,
      ...elevation.card,
    },
    surface,
    style,
  ];

  if (!onPress) return <View style={base}>{children}</View>;

  return (
    <Touchable onPress={onPress} weight="card" haptic={haptic} style={base}>
      {children}
    </Touchable>
  );
}
