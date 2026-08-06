import React from 'react';
import { View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { buildCardModel } from '@/saves/cardModel';
import { saveSubtitle, saveTitle } from '@/saves/format';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { Glyph } from './Glyph';
import { ListRow } from './ListRow';
import { SaveThumb } from './SaveThumb';
import { SwipeableRow } from './SwipeableRow';

/** The Library's multi-select checkbox — a hollow ring, or a filled tick. */
function SelectionMark({ selected }: { selected: boolean }) {
  const { palette } = useTheme();
  if (selected) {
    return (
      <View
        style={{
          width: 22,
          height: 22,
          borderRadius: 11,
          backgroundColor: palette.accent,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Glyph name="check" size={13} weight={2.5} color={palette.background} />
      </View>
    );
  }
  return <Glyph name="ring" size={22} weight={2} color={palette.textFaint} />;
}

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
  /** Enters multi-select — ignored while `selectionMode` is already active. */
  onLongPress?: () => void;
  subtitleOverride?: string;
  /** True while the Library is in multi-select. Suspends swipe and shows the checkbox instead of the thumbnail's type icon. */
  selectionMode?: boolean;
  selected?: boolean;
  /** Swipe-right action. Omitted (not just disabled) while `selectionMode` is active — a drag can't fire a flag flip mid-selection. */
  onFavorite?: () => void;
  /** Swipe-left action, same rule as `onFavorite`. */
  onArchive?: () => void;
}

export function SaveCard({
  save,
  trailing,
  onPress,
  onLongPress,
  subtitleOverride,
  selectionMode,
  selected,
  onFavorite,
  onArchive,
}: SaveCardProps) {
  const { palette, spacing, radius } = useTheme();
  const model = buildCardModel(save);

  const favoriteBadge = save.favorite ? (
    <Glyph name="heart" size={13} weight={2} color={palette.accent} />
  ) : null;
  const selectionMark = selectionMode ? <SelectionMark selected={!!selected} /> : null;

  let card: React.ReactNode;

  if (!model) {
    const fallbackMeta = save.knowledgeType ? saveTypeMeta(save.knowledgeType) : undefined;
    card = (
      <ListRow
        title={saveTitle(save)}
        subtitle={subtitleOverride ?? saveSubtitle(save)}
        tint={fallbackMeta?.color}
        glyph={fallbackMeta?.glyph}
        thumbnailUrl={save.thumbnailUrl}
        leading={selectionMark}
        trailing={
          favoriteBadge || trailing ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
              {favoriteBadge}
              {trailing}
            </View>
          ) : undefined
        }
        onPress={onPress}
        onLongPress={onLongPress}
      />
    );
  } else {
    const cardMeta = saveTypeMeta(model.kind);
    card = (
      <Card onPress={onPress} onLongPress={onLongPress} radius={radius.md} padding={0} style={{ overflow: 'hidden' }}>
        <View style={{ flexDirection: 'row', gap: spacing.md, padding: spacing.smd, alignItems: 'flex-start' }}>
          {selectionMark ? <View style={{ paddingTop: 9 }}>{selectionMark}</View> : null}
          <SaveThumb
            thumbnailUrl={save.thumbnailUrl}
            width={40}
            height={40}
            radius={radius.sm}
            tint={cardMeta.color}
            glyph={cardMeta.glyph}
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
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
                {favoriteBadge}
                {trailing}
              </View>
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

  if (!onFavorite && !onArchive) return card;

  return (
    <SwipeableRow
      disabled={selectionMode}
      onFavorite={onFavorite}
      onArchive={onArchive}
    >
      {card}
    </SwipeableRow>
  );
}
