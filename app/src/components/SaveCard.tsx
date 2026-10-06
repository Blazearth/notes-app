import React from 'react';
import { ActivityIndicator, View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { canRetry, startRetry, useIsRetrying } from '@/saves/retry';
import { useSaves } from '@/saves/SavesProvider';
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
import { Touchable } from './Touchable';

const SOURCE_LABELS: Record<SaveResponse['sourceType'], string> = {
  url: 'Link',
  text: 'Note',
  image: 'Image',
  pdf: 'PDF',
  audio: 'Audio',
};

/**
 * A failed save's own compact row — deliberately not the rich type-specific
 * card, and not the plain `ListRow` fallback either. Neither of those makes
 * "this didn't work, here's what to do" the visual point; this one leads with
 * it, and never lets a failed capture read as though it succeeded.
 *
 * Shared by Home and Library because both render failed saves through this
 * same `SaveCard`, which is what item 6 of the design pass actually asked
 * for: one failure component, not two that can drift apart.
 */
function FailedSaveRow({
  save,
  selectionMark,
  onPress,
  onLongPress,
  retryDisabled,
}: {
  save: SaveResponse;
  selectionMark: React.ReactNode;
  onPress?: () => void;
  onLongPress?: () => void;
  /** True while the Library's multi-select is active — a tap should select, not retry. */
  retryDisabled?: boolean;
}) {
  const { palette, spacing, radius } = useTheme();
  const { saves } = useSaves();
  // Shared, not per-row: Home and Library show the same answer, and the attempt
  // is settled by `SavesProvider` whether or not this row is still mounted.
  const retrying = useIsRetrying(save.id);

  const handleRetry = () => {
    if (retrying || retryDisabled) return;
    startRetry(save, saves);
  };

  const canShowRetry = canRetry(save);
  const sourceLabel = save.sourceUrl || (save.sourceType === 'text' ? saveTitle(save) : SOURCE_LABELS[save.sourceType]);

  return (
    <Card padding={0} radius={radius.md} onPress={onPress} onLongPress={onLongPress} style={{ opacity: 0.92 }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.smd,
          padding: spacing.smd,
        }}
      >
        {selectionMark}
        <View
          style={{
            width: 32,
            height: 32,
            borderRadius: radius.sm,
            backgroundColor: `${palette.danger}26`,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="close" size={14} weight={2} color={palette.danger} />
        </View>
        <View style={{ flex: 1 }}>
          <AppText variant="label" numberOfLines={1} style={{ color: palette.danger, fontSize: 12 }}>
            Couldn't process this save
          </AppText>
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 1 }}>
            {sourceLabel}
          </AppText>
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ fontSize: 10, marginTop: 1 }}>
            {SOURCE_LABELS[save.sourceType]}
            {save.errorMessage ? ` · ${save.errorMessage}` : ' · We couldn’t process this save'}
          </AppText>
        </View>
        {retrying ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}>
            <ActivityIndicator size="small" color={palette.textMuted} />
            <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
              Retrying…
            </AppText>
          </View>
        ) : canShowRetry && !retryDisabled ? (
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Retry"
            onPress={handleRetry}
            haptic="selection"
            style={{
              paddingVertical: spacing.xs,
              paddingHorizontal: spacing.smd,
              borderRadius: radius.pill,
              borderWidth: 1,
              borderColor: palette.border,
            }}
          >
            <AppText variant="label" tone="accent" style={{ fontSize: 12 }}>
              Retry
            </AppText>
          </Touchable>
        ) : null}
      </View>
    </Card>
  );
}

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
  /** Swipe-left (full swipe, confirmed by the caller) or bulk action to permanently delete. */
  onDelete?: () => void;
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
  onDelete,
}: SaveCardProps) {
  const { palette, spacing, radius } = useTheme();
  const model = buildCardModel(save);

  const favoriteBadge = save.favorite ? (
    <Glyph name="heart" size={13} weight={2} color={palette.accent} />
  ) : null;
  const selectionMark = selectionMode ? <SelectionMark selected={!!selected} /> : null;

  let card: React.ReactNode;

  if (save.status === 'failed') {
    card = (
      <FailedSaveRow
        save={save}
        selectionMark={selectionMark}
        onPress={onPress}
        onLongPress={onLongPress}
        retryDisabled={selectionMode}
      />
    );
  } else if (!model) {
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

  if (!onFavorite && !onDelete) return card;

  return (
    <SwipeableRow disabled={selectionMode} onFavorite={onFavorite} onDelete={onDelete}>
      {card}
    </SwipeableRow>
  );
}
