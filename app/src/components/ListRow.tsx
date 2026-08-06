import React from 'react';
import { View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { SaveThumb } from './SaveThumb';

export interface ListRowProps {
  title: string;
  subtitle?: string;
  tint?: string;
  trailing?: React.ReactNode;
  onPress?: () => void;
  thumbnailUrl?: string | null;
}

export function ListRow({ title, subtitle, tint, trailing, onPress, thumbnailUrl }: ListRowProps) {
  const { spacing, radius, layout } = useTheme();

  return (
    <Card onPress={onPress} radius={radius.md} padding={0} style={{ overflow: 'hidden' }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
          paddingVertical: spacing.smd,
          paddingHorizontal: layout.rowPadding,
        }}
      >
        <SaveThumb
          thumbnailUrl={thumbnailUrl}
          width={40}
          height={40}
          radius={radius.sm}
          tint={tint}
        />
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle">{title}</AppText>
          {subtitle ? (
            <AppText variant="caption" tone="muted">
              {subtitle}
            </AppText>
          ) : null}
        </View>
        {trailing}
      </View>
    </Card>
  );
}
