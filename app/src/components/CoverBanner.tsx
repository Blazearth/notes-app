import { LinearGradient } from 'expo-linear-gradient';
import React from 'react';
import { View } from 'react-native';

import { withAlpha } from '@/theme/contrast';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * The personalised gradient wash behind the top of Home.
 *
 * PennyWise fades its cover out with a shader mask; RN has no cheap equivalent,
 * so the fade is a second gradient from transparent to the page background
 * stacked on top. Same result, no extra native dependency.
 */
export function CoverBanner() {
  const { cover, palette, layout, alpha } = useTheme();
  const colors = cover.colors;
  if (colors.length < 2) return null;

  return (
    <View
      pointerEvents="none"
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        height: layout.coverHeight,
        opacity: alpha.cover,
      }}
    >
      <LinearGradient
        colors={colors as [string, string, ...string[]]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 0 }}
        style={{ flex: 1 }}
      />
      <LinearGradient
        colors={[withAlpha(palette.background, 0), palette.background]}
        start={{ x: 0, y: 0 }}
        end={{ x: 0, y: 1 }}
        style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: layout.coverHeight * 0.65 }}
      />
    </View>
  );
}
