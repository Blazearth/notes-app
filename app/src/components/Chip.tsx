import React from 'react';
import { View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Touchable } from './Touchable';

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
    <Touchable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={onPress}
      // A filter strip is swept through, so `selection` rather than `light` —
      // the softer tick reads as moving along a set instead of N separate taps.
      haptic="selection"
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.sm,
        paddingVertical: spacing.sm,
        paddingHorizontal: spacing.lg,
        borderRadius: radius.pill,
        backgroundColor: selected ? palette.text : palette.surface,
        borderWidth: selected ? 0 : 1,
        borderColor: selected ? 'transparent' : palette.border,
      }}
    >
      {tint ? <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: tint }} /> : null}
      <AppText
        variant={selected ? 'label' : 'bodySmall'}
        style={{ color: selected ? palette.background : palette.textMuted }}
      >
        {label}
      </AppText>
    </Touchable>
  );
}
