import React from 'react';
import { View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import type { GlyphName } from './Glyph';
import { SaveThumb } from './SaveThumb';

export interface ListRowProps {
  title: string;
  subtitle?: string;
  tint?: string;
  glyph?: GlyphName;
  trailing?: React.ReactNode;
  /** Rendered before the thumbnail — the Library multi-select checkbox. */
  leading?: React.ReactNode;
  onPress?: () => void;
  onLongPress?: () => void;
  thumbnailUrl?: string | null;
}

export function ListRow({
  title,
  subtitle,
  tint,
  glyph,
  trailing,
  leading,
  onPress,
  onLongPress,
  thumbnailUrl,
}: ListRowProps) {
  const { spacing, radius, layout } = useTheme();

  return (
    <Card onPress={onPress} onLongPress={onLongPress} radius={radius.md} padding={0} style={{ overflow: 'hidden' }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
          paddingVertical: spacing.smd,
          paddingHorizontal: layout.rowPadding,
        }}
      >
        {leading}
        <SaveThumb
          thumbnailUrl={thumbnailUrl}
          width={40}
          height={40}
          radius={radius.sm}
          tint={tint}
          glyph={glyph}
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
