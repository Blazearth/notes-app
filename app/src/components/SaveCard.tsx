import React from 'react';
import { View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { buildCardModel } from '@/saves/cardModel';
import { saveSubtitle, saveTitle } from '@/saves/format';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { HatchThumb } from './HatchThumb';
import { ListRow } from './ListRow';

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
  /**
   * Replaces the derived meta line with a caller-supplied one — Home uses it
   * for "YouTube • Workout", where source and category say more at a glance
   * than the knowledge type does.
   *
   * An override rather than a flag so the decision stays with the screen: the
   * Library wants the type, Home wants the provenance, and neither is more
   * correct in general.
   */
  subtitleOverride?: string;
}

/**
 * A save's row in the feed — recipe, movie and place get the bespoke layout
 * from `buildCardModel`; everything else (still processing, `unusable`, or a
 * knowledge type without a layout yet) falls back to the flat `ListRow`, so
 * this is the only branch point a screen needs to know about.
 */
export function SaveCard({ save, trailing, onPress, subtitleOverride }: SaveCardProps) {
  const { spacing, radius } = useTheme();
  const model = buildCardModel(save);

  if (!model) {
    return (
      <ListRow
        title={saveTitle(save)}
        subtitle={subtitleOverride ?? saveSubtitle(save)}
        tint={save.knowledgeType ? TYPE_COLORS[save.knowledgeType] : undefined}
        trailing={trailing}
        onPress={onPress}
      />
    );
  }

  return (
    <Card onPress={onPress} radius={radius.md} padding={0} style={{ overflow: 'hidden' }}>
      <View style={{ flexDirection: 'row', gap: spacing.md, padding: spacing.smd }}>
        <View>
          <HatchThumb width={40} height={40} radius={radius.sm} period={12} />
          <View
            style={{
              position: 'absolute',
              right: -2,
              bottom: -2,
              width: 10,
              height: 10,
              borderRadius: 5,
              backgroundColor: TYPE_COLORS[model.kind] ?? TYPE_COLORS.other,
            }}
          />
        </View>
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
