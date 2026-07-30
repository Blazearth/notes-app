import React from 'react';
import { View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { HatchThumb } from './HatchThumb';

export interface ListRowProps {
  title: string;
  subtitle?: string;
  /** Colour pip overlaid on the thumbnail, keyed off the knowledge type. */
  tint?: string;
  trailing?: React.ReactNode;
  onPress?: () => void;
}

/** The "Recently captured" / "Recently organized" row from the mockups. */
export function ListRow({ title, subtitle, tint, trailing, onPress }: ListRowProps) {
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
        <View>
          <HatchThumb width={40} height={40} radius={radius.sm} period={12} />
          {tint ? (
            <View
              style={{
                position: 'absolute',
                right: -2,
                bottom: -2,
                width: 10,
                height: 10,
                borderRadius: 5,
                backgroundColor: tint,
              }}
            />
          ) : null}
        </View>
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
