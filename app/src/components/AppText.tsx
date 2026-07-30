import React from 'react';
import { StyleSheet, Text, type TextProps, type TextStyle } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import type { Typography } from '@/theme/typography';

type Variant = keyof Typography;
type Tone = 'default' | 'muted' | 'faint' | 'accent' | 'onAccent' | 'onAccentContainer' | 'inverse';

export interface AppTextProps extends TextProps {
  variant?: Variant;
  tone?: Tone;
}

/**
 * Every string in the app goes through here so a font or palette change is one
 * re-render rather than a search-and-replace.
 */
export function AppText({ variant = 'body', tone = 'default', style, ...rest }: AppTextProps) {
  const { type, palette } = useTheme();

  const color: Record<Tone, string> = {
    default: palette.text,
    muted: palette.textMuted,
    faint: palette.textFaint,
    accent: palette.accentText,
    onAccent: palette.onAccent,
    onAccentContainer: palette.onAccentContainer,
    inverse: palette.isDark ? palette.background : '#FFFFFF',
  };

  const base = type[variant] as TextStyle;
  return <Text {...rest} style={StyleSheet.flatten([base, { color: color[tone] }, style])} />;
}
