import React from 'react';
import { View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { buildCardModel } from '@/saves/cardModel';
import { saveSubtitle, saveTitle } from '@/saves/format';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { ListRow } from './ListRow';
import { SaveThumb } from './SaveThumb';

function Tag({ label }: { label: string }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <View
      style={{
        paddingVertical: 3,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.pill,
        backgroundColor: palette.surfaceVariant,
        borderWidth: 1,
        borderColor: palette.border,
      }}
    >
      <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
        {label}
      </AppText>
    </View>
  );
}

export interface SaveCardProps {
  save: SaveResponse;
  trailing?: React.ReactNode;
  onPress?: () => void;
  subtitleOverride?: string;
}

export function SaveCard({ save, trailing, onPress, subtitleOverride }: SaveCardProps) {
  const { spacing, radius } = useTheme();
  const model = buildCardModel(save);

  if (!model) {
    return (
      <ListRow
        title={saveTitle(save)}
        subtitle={subtitleOverride ?? saveSubtitle(save)}
        tint={save.knowledgeType ? TYPE_COLORS[save.knowledgeType] : undefined}
        thumbnailUrl={save.thumbnailUrl}
        trailing={trailing}
        onPress={onPress}
      />
    );
  }

  return (
    <Card onPress={onPress} radius={radius.md} padding={0} style={{ overflow: 'hidden' }}>
      <View style={{ flexDirection: 'row', gap: spacing.md, padding: spacing.smd }}>
        <SaveThumb
          thumbnailUrl={save.thumbnailUrl}
          width={40}
          height={40}
          radius={radius.sm}
          tint={TYPE_COLORS[model.kind] ?? TYPE_COLORS.other}
        />
        <View style={{ flex: 1 }}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'flex-start',
              justifyContent: 'space-between',
              gap: spacing.sm,
            }}
          >
            <AppText variant="cardTitle" style={{ flex: 1 }} numberOfLines={1}>
              {model.title}
            </AppText>
            {trailing}
          </View>
          {subtitleOverride ?? model.meta ? (
            <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
              {subtitleOverride ?? model.meta}
            </AppText>
          ) : null}
          {model.summary ? (
            <AppText variant="bodySmall" tone="muted" numberOfLines={2} style={{ marginTop: spacing.xs }}>
              {model.summary}
            </AppText>
          ) : null}
          {model.chips && model.chips.length > 0 ? (
            <View
              style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xs }}
            >
              {model.chips.map((chip) => (
                <Tag key={chip} label={chip} />
              ))}
              {model.chipsOverflow ? <Tag label={`+${model.chipsOverflow}`} /> : null}
            </View>
          ) : null}
        </View>
      </View>
    </Card>
  );
}
