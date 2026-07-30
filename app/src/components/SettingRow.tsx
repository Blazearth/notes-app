import React from 'react';
import { Pressable, Switch, View } from 'react-native';

import { withAlpha } from '@/theme/contrast';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

interface BaseProps {
  title: string;
  description?: string;
}

export function SettingSwitch({
  title,
  description,
  value,
  onValueChange,
  disabled,
}: BaseProps & { value: boolean; onValueChange: (v: boolean) => void; disabled?: boolean }) {
  const { palette, spacing, alpha } = useTheme();

  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.lg,
        paddingVertical: spacing.md,
        opacity: disabled ? alpha.disabled : 1,
      }}
    >
      <View style={{ flex: 1 }}>
        <AppText variant="bodySmall" style={{ fontSize: 14 }}>
          {title}
        </AppText>
        {description ? (
          <AppText variant="caption" tone="muted">
            {description}
          </AppText>
        ) : null}
      </View>
      <Switch
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        trackColor={{ true: withAlpha(palette.accent, 0.55), false: palette.surfaceVariant }}
        thumbColor={value ? palette.accent : palette.surface}
        ios_backgroundColor={palette.surfaceVariant}
      />
    </View>
  );
}

export function SettingLink({
  title,
  description,
  value,
  onPress,
}: BaseProps & { value?: string; onPress: () => void }) {
  const { spacing } = useTheme();

  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        {
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.lg,
          paddingVertical: spacing.md,
        },
        pressed && { opacity: 0.7 },
      ]}
    >
      <View style={{ flex: 1 }}>
        <AppText variant="bodySmall" style={{ fontSize: 14 }}>
          {title}
        </AppText>
        {description ? (
          <AppText variant="caption" tone="muted">
            {description}
          </AppText>
        ) : null}
      </View>
      {value ? (
        <AppText variant="bodySmall" tone="accent">
          {value}
        </AppText>
      ) : null}
    </Pressable>
  );
}
