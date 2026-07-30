import React from 'react';
import { Pressable, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

export interface ChipProps {
  label: string;
  selected?: boolean;
  onPress?: () => void;
  /** Small colour pip, used for knowledge-type filters. */
  tint?: string;
}

/**
 * Library filter chip. Selected state in the mockups is the ink fill (not the
 * accent), which keeps the accent reserved for the FAB and the digest card.
 */
export function Chip({ label, selected = false, onPress, tint }: ChipProps) {
  const { palette, radius, spacing } = useTheme();

  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        {
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.sm,
          paddingVertical: spacing.sm,
          paddingHorizontal: spacing.lg,
          borderRadius: radius.pill,
          backgroundColor: selected ? palette.text : palette.surface,
          borderWidth: selected ? 0 : 1,
          borderColor: selected ? 'transparent' : palette.border,
        },
        pressed && { opacity: 0.8 },
      ]}
    >
      {tint ? <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: tint }} /> : null}
      <AppText
        variant={selected ? 'label' : 'bodySmall'}
        style={{ color: selected ? palette.background : palette.textMuted }}
      >
        {label}
      </AppText>
    </Pressable>
  );
}
