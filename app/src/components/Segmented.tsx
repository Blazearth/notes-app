import React from 'react';
import { Pressable, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
}

export interface SegmentedProps<T extends string> {
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
}

/** The Spaces tab strip (Saves / Chat / Tasks / Calendar), reused in Settings. */
export function Segmented<T extends string>({ options, value, onChange }: SegmentedProps<T>) {
  const { palette, radius, spacing } = useTheme();

  return (
    <View
      style={{
        flexDirection: 'row',
        gap: spacing.xs + 2,
        backgroundColor: palette.surfaceMuted,
        borderRadius: radius.md - 2,
        padding: spacing.xs,
      }}
    >
      {options.map((option) => {
        const active = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            onPress={() => onChange(option.value)}
            style={{
              flex: 1,
              alignItems: 'center',
              paddingVertical: spacing.sm,
              borderRadius: radius.sm - 1,
              backgroundColor: active ? palette.surface : 'transparent',
            }}
          >
            <AppText
              variant={active ? 'label' : 'bodySmall'}
              tone={active ? 'default' : 'muted'}
              style={{ fontSize: 12 }}
            >
              {option.label}
            </AppText>
          </Pressable>
        );
      })}
    </View>
  );
}
