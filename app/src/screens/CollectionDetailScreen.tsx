import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Image, Modal, ScrollView, TextInput, View } from 'react-native';

import type { CollectionEntityResponse, CollectionNodeResponse, SaveResponse } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { useLiveValue } from '@/local';
import {
  DERIVED_TABLES,
  readCollectionEntities,
  readCollectionNode,
  readCollectionSources,
} from '@/local/derived';
import { writeEntityState } from '@/local/writes';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { StatusPill, StarRating } from '@/components/EntityControls';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Segmented } from '@/components/Segmented';
import { Touchable } from '@/components/Touchable';
import {
  collectionTypeMeta,
  nodeDepth,
  nodeEntityNoun,
  nodeType,
  type CollectionTab,
} from '@/collections/collectionMeta';
import { entityDetailFields, entityMetaLine } from '@/collections/entityFields';
import {
  bySection,
  currentStatus,
  nextStatus,
  stateForStatus,
  statusesFor,
} from '@/collections/entityStatus';
import { sourceSummaries, type SourceSummary } from '@/collections/sourceSummary';
import { groupByDay } from '@/collections/placeGroups';
import { musclesInSplit } from '@/collections/axes';
import { estimateSessionLoad } from '@/collections/workoutLoad';
import { buildCandidate } from '@/collections/nextAction';
import { NextActionCard } from '@/components/NextActionCard';
import { displayName } from '@/knowledge/facets';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * How this screen names its own "next up" nudge, per type — "Next up" reads
 * as a plain state ("this is what you're already doing / should do next"),
 * not a generated recommendation, which is why it stays the default label
 * rather than something that implies Weavr is picking on your behalf.
 * "Keep going" over a checklist that is already underway is the one
 * exception, since a checklist genuinely isn't a "next up" until it's
 * started. Workout has no entry: its own action button and session-load
 * card (below) already do this job, and a second nudge card would be the
 * same suggestion said twice on one screen.
 */
const NEXT_ACTION_LABELS: Record<string, string> = {
  recommendation_list: 'Next up',
  itinerary: 'Plan ahead',
  checklist: 'Keep going',
};

/** Stable identities for the "nothing derived yet" cases — see `useLiveValue`. */
const EMPTY_ENTITIES: CollectionEntityResponse[] = [];
const EMPTY_SAVES: SaveResponse[] = [];

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== '[unclear]' ? trimmed : null;
}

function sourceLabel(count: number): string {
  return `${count} ${count === 1 ? 'source' : 'sources'}`;
}

function capitalise(value: string): string {
  return value ? value[0].toUpperCase() + value.slice(1) : value;
}

/**
 * Every usable raw `muscleGroups` value across a set of source saves — the
 * session-level facet `workout`'s axis already derives splits from, read back
 * out for display. Screen-local: nothing outside the workout dashboard needs
 * a raw union of this field.
 */
function rawMuscleGroups(saves: SaveResponse[]): string[] {
  const out: string[] = [];
  for (const save of saves) {
    const raw = save.structuredData?.muscleGroups;
    if (Array.isArray(raw)) {
      for (const value of raw) if (typeof value === 'string') out.push(value);
    }
  }
  return out;
}

/**
 * One folder in this collection — Japan under Itineraries, Romance under
 * Anime.
 *
 * A row rather than a tile, for `GroupDetailScreen`'s reason: folder names
 * run long ("Slice of Life", "Switzerland") and a grid of those is mostly
 * truncation. The counts carry the information and the chevron the
 * affordance.
 */
function SubgroupRow({
  node,
  type,
  muscles,
  onPress,
}: {
  node: CollectionNodeResponse;
  type: string;
  /** Workout only — the raw muscle groups this split's own sources train, e.g. "Back, Rear delts, Biceps" for Pull. */
  muscles?: string[];
  onPress: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const meta = collectionTypeMeta(type);
  const depth = nodeDepth(node.id);

  const parts = [`${node.entityCount} ${nodeEntityNoun(type, node.id, node.entityCount)}`];
  if (node.subgroups.length > 0) {
    parts.push(`${node.subgroups.length} ${meta.groupNoun(node.subgroups.length, depth)}`);
  }
  parts.push(sourceLabel(node.sourceCount));
  if (node.doneCount > 0) parts.push(`${node.doneCount} ${meta.doneNoun}`);

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${node.name}, ${parts.join(', ')}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        <View
          style={{
            width: 34,
            height: 34,
            borderRadius: radius.sm,
            backgroundColor: palette.surfaceVariant,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="folder" size={16} weight={2} color={palette.textMuted} />
        </View>
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={1}>
            {node.name}
          </AppText>
          {muscles && muscles.length > 0 ? (
            <AppText variant="caption" tone="accent" numberOfLines={1} style={{ marginTop: 2 }}>
              {muscles.join(' · ')}
            </AppText>
          ) : null}
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
            {parts.join(' · ')}
          </AppText>
        </View>
        <View style={{ transform: [{ scaleX: -1 }] }}>
          <Glyph name="chevron" size={icon.sm} color={palette.textFaint} />
        </View>
      </Touchable>
    </Card>
  );
}

/**
 * A small tappable done/visited tick — the sibling of `StatusPill` for a type
 * with no status model (itinerary, checklist). Sits beside the row's own
 * name Touchable rather than nested inside it, so the two never fight over
 * the same tap: a row like this is two independent hit targets side by side,
 * the same shape `StatusPill` already has next to the title above it.
 */
function DoneToggle({
  done,
  doneNoun,
  name,
  compact,
  onPress,
}: {
  done: boolean;
  doneNoun: string;
  name: string;
  /** Icon-only, for the compact (itinerary) row — a label here would be the thing making 45 rows tall again. */
  compact?: boolean;
  onPress: () => void;
}) {
  const { palette, spacing } = useTheme();
  return (
    <Touchable
      accessibilityRole="checkbox"
      accessibilityState={{ checked: done }}
      accessibilityLabel={done ? `Mark ${name} as not ${doneNoun}` : `Mark ${name} ${doneNoun}`}
      onPress={onPress}
      haptic="light"
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
    >
      <Glyph name={done ? 'checkSquare' : 'square'} size={16} color={done ? palette.accent : palette.textFaint} />
      {compact ? null : (
        <AppText variant="label" tone={done ? 'accent' : 'muted'}>
          {done ? capitalise(doneNoun) : `Mark ${doneNoun}`}
        </AppText>
      )}
    </Touchable>
  );
}

/**
 * One entity, as a row, with whatever controls its type actually supports.
 *
 * The status pill sits **on the row**, not behind a tap into the sheet.
 * Setting a title to Watching is the single most common thing anyone does on
 * a watchlist, and putting it two taps away turns the screen back into a
 * read-only list of extractions. Types with no status model get a plain
 * `DoneToggle` in the same spot — visited/done is a one-tap flip here too,
 * not something that only exists inside the detail sheet. Rating is
 * deliberately *not* here — five stars repeated down a list of dozens of
 * titles is noise, and rating is a considered action that belongs in the
 * detail sheet, not a thing to manage while browsing.
 *
 * The source count is worded "in N saves" and only shown when it is greater
 * than one, where it says *this keeps coming up*. "1 source" on every row is
 * noise that makes the extraction structure visible for no benefit.
 *
 * `collMeta.compactList` types (itinerary) render as a single dense line
 * instead — no poster tile, tighter padding — because a destination can hold
 * dozens of places and a portrait tile per row is mostly whitespace at that
 * count. The done toggle moves inline as an icon-only tap target next to the
 * chevron rather than a second row, for the same reason.
 */
function EntityRow({
  entity,
  type,
  number,
  onPress,
  onCycleStatus,
  onToggleDone,
}: {
  entity: CollectionEntityResponse;
  type: string;
  /** Workout and itinerary — 1-based position within its section. */
  number?: number;
  onPress: () => void;
  onCycleStatus: () => void;
  onToggleDone: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const typeMeta = saveTypeMeta(type);
  const collMeta = collectionTypeMeta(type);
  const posterUrl = clean(entity.fields.posterUrl);
  const done = entity.state?.done === true;
  const pinned = entity.state?.pinned === true;
  const crossSource = entity.sourceCount > 1;
  const status = currentStatus(type, entity.state);

  const kind = collMeta.showsKind ? clean(entity.kind) : null;
  const meta = entityMetaLine(type, kind, entity.fields);
  const metaLine = [meta, crossSource ? `in ${entity.sourceCount} saves` : null].filter(Boolean).join(' · ');

  if (collMeta.compactList) {
    return (
      <Card padding={0} radius={radius.md}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.smd,
            paddingVertical: spacing.sm + 2,
            paddingHorizontal: spacing.md,
          }}
        >
          <Touchable
            accessibilityRole="button"
            accessibilityLabel={crossSource ? `${entity.name}, in ${entity.sourceCount} saves` : entity.name}
            onPress={onPress}
            haptic="selection"
            style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}
          >
            {typeof number === 'number' ? (
              <AppText variant="caption" tone="muted" style={{ width: 20 }}>
                {String(number).padStart(2, '0')}
              </AppText>
            ) : null}
            <View style={{ flex: 1 }}>
              <AppText variant="cardTitle" numberOfLines={1}>
                {entity.name}
              </AppText>
              {metaLine ? (
                <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 1 }}>
                  {metaLine}
                </AppText>
              ) : null}
            </View>
          </Touchable>
          {pinned ? <Glyph name="bookmark" size={14} weight={2} color={palette.accent} /> : null}
          <DoneToggle
            done={done}
            doneNoun={collMeta.doneNoun}
            name={entity.name}
            compact
            onPress={onToggleDone}
          />
          <View style={{ transform: [{ scaleX: -1 }] }}>
            <Glyph name="chevron" size={icon.sm} color={palette.textFaint} />
          </View>
        </View>
      </Card>
    );
  }

  return (
    <Card padding={0} radius={radius.md}>
      <View style={{ padding: spacing.md, gap: spacing.sm }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={crossSource ? `${entity.name}, in ${entity.sourceCount} saves` : entity.name}
          onPress={onPress}
          haptic="selection"
          style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}
        >
          {typeof number === 'number' ? (
            <AppText variant="caption" tone="muted" style={{ width: 20 }}>
              {String(number).padStart(2, '0')}
            </AppText>
          ) : null}
          {posterUrl ? (
            <Image
              source={{ uri: posterUrl }}
              style={{ width: 36, height: 54, borderRadius: radius.sm }}
              resizeMode="cover"
            />
          ) : (
            <View
              style={{
                width: 36,
                height: 54,
                borderRadius: radius.sm,
                backgroundColor: `${typeMeta.color}26`,
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Glyph name={typeMeta.glyph} size={16} weight={2} color={typeMeta.color} />
            </View>
          )}
          <View style={{ flex: 1 }}>
            <AppText variant="cardTitle" numberOfLines={1}>
              {entity.name}
            </AppText>
            {metaLine ? (
              <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
                {metaLine}
              </AppText>
            ) : null}
          </View>
          {pinned ? <Glyph name="bookmark" size={14} weight={2} color={palette.accent} /> : null}
        </Touchable>

        {/* The action strip: the status pill for a type with a status model,
            a plain done toggle otherwise. Always something tappable — the
            detail sheet is no longer the only place this can be set. */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md, flexWrap: 'wrap' }}>
          {status ? (
            <StatusPill status={status} name={entity.name} onPress={onCycleStatus} />
          ) : (
            <DoneToggle done={done} doneNoun={collMeta.doneNoun} name={entity.name} onPress={onToggleDone} />
          )}
        </View>
      </View>
    </Card>
  );
}

/**
 * One source save, as a card — the un-merged view.
 *
 * Shows the source's own *shape*: "Tokyo → Mount Fuji → Hiroshima → Kyoto…",
 * which is what a person recognises a saved trip by, and which the merged
 * place list cannot express because a set has no order.
 */
function SourceCard({ summary, onPress }: { summary: SourceSummary; onPress: () => void }) {
  const { palette, radius, spacing, icon } = useTheme();
  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${summary.title}${summary.meta ? `, ${summary.meta}` : ''}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={2}>
            {summary.title}
          </AppText>
          {summary.meta ? (
            <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
              {summary.meta}
            </AppText>
          ) : null}
          {summary.outline ? (
            <AppText variant="bodySmall" tone="muted" numberOfLines={2} style={{ marginTop: spacing.xs }}>
              {summary.outline}
            </AppText>
          ) : null}
        </View>
        <View style={{ transform: [{ scaleX: -1 }] }}>
          <Glyph name="chevron" size={icon.sm} color={palette.textFaint} />
        </View>
      </Touchable>
    </Card>
  );
}

/**
 * The entity detail sheet — everything the merged entity says, plus every
 * source's own account of it, attributed to the save it came from.
 *
 * A plain `Modal` rather than the shared `Sheet` chrome: `Sheet` drives its
 * dismissal through `router.back()`, which assumes it is its own route — this
 * opens from data the collection screen already holds, so a route (and the
 * entity-key path-encoding it would need) buys nothing.
 */
function EntityDetailSheet({
  entity,
  type,
  sourceTitles,
  onClose,
  onOpenSave,
  onCycleStatus,
  onToggleDone,
  onRate,
  onTogglePin,
}: {
  entity: CollectionEntityResponse;
  type: string;
  sourceTitles: Map<string, string>;
  onClose: () => void;
  onOpenSave: (saveId: string) => void;
  onCycleStatus: () => void;
  onToggleDone: () => void;
  onRate: (rating: number) => void;
  onTogglePin: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const collMeta = collectionTypeMeta(type);
  const done = entity.state?.done === true;
  const pinned = entity.state?.pinned === true;
  const rating = typeof entity.state?.rating === 'number' ? entity.state.rating : 0;
  const status = currentStatus(type, entity.state);

  // `reason` is excluded from the headline fields on purpose: the rolled-up
  // value is one source's, chosen arbitrarily by the scalar rollup, and every
  // source's own reason is already shown attributed below. A headline "Why"
  // would present one recommender's words as the collective view.
  const fields = entityDetailFields(type, entity.fields, ['reason', 'detail']);

  return (
    <Modal transparent animationType="slide" visible onRequestClose={onClose}>
      <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: palette.scrim }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Dismiss"
          onPress={onClose}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        />
        <View
          style={{
            backgroundColor: palette.surface,
            borderTopLeftRadius: radius.xl,
            borderTopRightRadius: radius.xl,
            padding: spacing.lg,
            maxHeight: '85%',
          }}
        >
          <View
            style={{
              width: 36,
              height: 4,
              borderRadius: 2,
              backgroundColor: palette.border,
              alignSelf: 'center',
              marginBottom: spacing.lg,
            }}
          />
          <ScrollView showsVerticalScrollIndicator={false}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'flex-start',
                justifyContent: 'space-between',
                gap: spacing.sm,
                marginBottom: spacing.xs,
              }}
            >
              <AppText variant="heading" style={{ flex: 1 }}>
                {entity.name}
              </AppText>
              <Touchable
                accessibilityRole="button"
                accessibilityState={{ selected: pinned }}
                accessibilityLabel={pinned ? 'Unpin' : 'Pin to top'}
                onPress={onTogglePin}
                haptic="selection"
                style={{ paddingTop: 4 }}
              >
                <Glyph name="bookmark" size={20} weight={2} color={pinned ? palette.accent : palette.textFaint} />
              </Touchable>
            </View>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.lg }}>
              {entityMetaLine(type, collMeta.showsKind ? clean(entity.kind) : null, entity.fields) ??
                `In ${entity.sourceCount} ${entity.sourceCount === 1 ? 'save' : 'saves'}`}
            </AppText>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginBottom: spacing.xl, flexWrap: 'wrap' }}>
              {status ? (
                <StatusPill status={status} name={entity.name} onPress={onCycleStatus} />
              ) : (
                <Touchable
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: done }}
                  accessibilityLabel={done ? `Mark as not ${collMeta.doneNoun}` : `Mark ${collMeta.doneNoun}`}
                  onPress={onToggleDone}
                  haptic="light"
                  style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
                >
                  <Glyph name={done ? 'checkSquare' : 'square'} size={18} color={done ? palette.accent : palette.textMuted} />
                  <AppText variant="label" tone={done ? 'accent' : 'muted'}>
                    {done ? capitalise(collMeta.doneNoun) : `Mark ${collMeta.doneNoun}`}
                  </AppText>
                </Touchable>
              )}
              {collMeta.ratable ? <StarRating rating={rating} name={entity.name} onRate={onRate} size={16} /> : null}
            </View>

            {/*
              What the merge actually produced. A list field is the union
              across sources, a scalar the first source that stated it — which
              is why this is worth showing rather than sending the user back
              to one save: it is the combined answer, not any one source's.
            */}
            {fields.length > 0 ? (
              <>
                <SectionLabel>Details</SectionLabel>
                <View style={{ gap: spacing.sm, marginBottom: spacing.lg }}>
                  {fields.map((field) => (
                    <View key={field.label} style={{ flexDirection: 'row', gap: spacing.md }}>
                      <AppText variant="caption" tone="muted" style={{ width: 96 }}>
                        {field.label}
                      </AppText>
                      <AppText variant="bodySmall" style={{ flex: 1 }}>
                        {field.value}
                      </AppText>
                    </View>
                  ))}
                </View>
              </>
            ) : null}

            {/*
              "Mentioned in 3 saves", with the saves named. The user should
              never have to think "this Tokyo came from that reel", but *how
              many* of their saves agree on something is exactly the signal a
              pile of individual saves cannot give them.
            */}
            <SectionLabel>
              {entity.sourceCount === 1 ? 'From one save' : `Mentioned in ${entity.sourceCount} saves`}
            </SectionLabel>
            <View style={{ gap: spacing.smd, marginBottom: spacing.lg }}>
              {entity.sources.map((source) => {
                const reason = clean(source.item.reason) ?? clean(source.item.detail);
                const title = sourceTitles.get(source.saveId);
                return (
                  <Card key={source.saveId} radius={radius.md}>
                    {title ? (
                      <AppText variant="label" numberOfLines={2} style={{ marginBottom: reason ? spacing.xs : spacing.sm }}>
                        {title}
                      </AppText>
                    ) : null}
                    {reason ? <AppText style={{ marginBottom: spacing.sm }}>{reason}</AppText> : null}
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel="Open source save"
                      onPress={() => onOpenSave(source.saveId)}
                      haptic="light"
                      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
                    >
                      <Glyph name="link" size={icon.sm} color={palette.accent} />
                      <AppText variant="label" tone="accent">
                        Open source
                      </AppText>
                    </Touchable>
                  </Card>
                );
              })}
            </View>
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

/**
 * One node of the collection tree — a type root (Itineraries), a folder
 * (Japan), or a nested folder (Anime → Romance). One screen serves every
 * level: it lists whatever folders this node has, then whatever entities sit
 * directly on it, and pushes itself for a child. The navigation stack is the
 * breadcrumb trail, back always goes up exactly one level, and arbitrary
 * depth costs no extra screen — the same property `GroupDetailScreen` gets
 * from the group tree.
 *
 * A **leaf** node additionally gets a tab strip, per its type's
 * `tabs` (`collectionMeta`): the entity list always, plus an overview of the
 * sources that formed it and the sources themselves where those are worth
 * their own tab. A node *with folders* never does — offering a tab strip and
 * a folder list at once gives two competing ways down.
 *
 * `nodeId` is the tree's own id (`itinerary~japan`), so a deep link is just a
 * node id and needs no separate route per level.
 */
export function CollectionDetailScreen({ nodeId }: { nodeId: string }) {
  const { palette, spacing } = useTheme();
  const router = useRouter();

  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [tab, setTab] = useState<CollectionTab | null>(null);
  // Itinerary only, and only worth showing past a handful of places — see
  // `SHOW_PLACE_SEARCH` below.
  const [placeQuery, setPlaceQuery] = useState('');

  const type = nodeType(nodeId);

  useEffect(() => {
    track(AnalyticsEvent.CollectionOpened, { collection_type: type });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeId]);

  // All derived from the local store by the same merge core the server runs
  // (`@/collections/merge` + `@/local/derived`), so there is no fetch and no
  // error path: a node that does not exist locally is one whose saves have
  // not synced yet, not a failed request.
  const node = useLiveValue<CollectionNodeResponse | null>(
    DERIVED_TABLES,
    (store) => readCollectionNode(store, nodeId),
    null,
    [nodeId],
  );
  const entities = useLiveValue<CollectionEntityResponse[]>(
    DERIVED_TABLES,
    (store) => readCollectionEntities(store, nodeId),
    EMPTY_ENTITIES,
    [nodeId],
  );
  const sources = useLiveValue<SaveResponse[]>(
    DERIVED_TABLES,
    (store) => readCollectionSources(store, nodeId),
    EMPTY_SAVES,
    [nodeId],
  );

  const collMeta = collectionTypeMeta(type);
  const typeMeta = saveTypeMeta(type);
  const depth = nodeDepth(nodeId);

  const summaries = useMemo(() => sourceSummaries(sources), [sources]);
  const sourceTitles = useMemo(
    () => new Map(summaries.map((s) => [s.saveId, s.title])),
    [summaries],
  );

  // Entities shown *here* are the ones this node holds directly: anything in
  // a folder is reached through the folder instead, so nothing is listed
  // twice on one screen.
  const subgroups = node?.subgroups ?? [];
  const hasFolders = subgroups.length > 0;
  const ownKeys = useMemo(() => new Set(node?.entityKeys ?? []), [node]);
  const shown = useMemo(
    () => (hasFolders ? entities.filter((e) => ownKeys.has(e.entityKey)) : entities),
    [hasFolders, entities, ownKeys],
  );

  /**
   * Workout-only, and pure local compute — no request, same cost class as
   * `detailModel.ts`'s own `workoutLoadField`. `sources` is already scoped to
   * *this* node by `readCollectionSources` (the whole type for the root, one
   * split's own sources for a leaf), so both the estimate and the muscle
   * union are already the right subset with no extra filtering here.
   */
  const isWorkout = type === 'workout';
  const isItinerary = type === 'itinerary';
  const workoutSummary = useMemo(
    () => (isWorkout && !hasFolders ? estimateSessionLoad(shown.map((e) => e.fields)) : null),
    [isWorkout, hasFolders, shown],
  );
  const splitMuscleNames = useMemo(
    () => (isWorkout && !hasFolders && node ? musclesInSplit(rawMuscleGroups(sources), node.name) : []),
    [isWorkout, hasFolders, node, sources],
  );
  // For the root "Workouts" screen: `sources` already holds every save behind
  // every split (root `mergeNode` applies no facet filter), so each split's
  // own muscle union is a lookup by its own `saveIds` rather than a second
  // fetch.
  const sourceById = useMemo(() => new Map(sources.map((s) => [s.id, s])), [sources]);

  /**
   * This node's own "next up" nudge — pure local compute over data already
   * on this screen, never a request. `buildCandidate` already returns null
   * for a node with folders under it and for a type below its own threshold,
   * so no extra gating is needed here beyond excluding workout (see
   * `NEXT_ACTION_LABELS`).
   */
  const nextAction = useMemo(
    () => (node && !isWorkout ? buildCandidate(type, node, entities) : null),
    [node, isWorkout, type, entities],
  );

  // Pinned first within each section — a curation signal, not a re-sort of
  // the whole list.
  const byPinned = (a: CollectionEntityResponse, b: CollectionEntityResponse) =>
    Number(b.state?.pinned === true) - Number(a.state?.pinned === true);

  /**
   * Sections are, in order of preference: an itinerary's places grouped by
   * day (the fix for "45 identical cards" — a person reads a trip by when
   * they're somewhere, not by a visited/not-visited split; grouping by
   * `area` was tried and rejected, see `placeGroups.ts`), the type's own
   * status model where it has one (Want to watch / Watching / Watched), and
   * a plain open/done split otherwise. All three drop empty sections, so a
   * fresh collection is one list rather than headings over nothing.
   */
  // A search box earns its space past a handful of places — below that a
  // destination's whole list is already shorter than the box itself.
  const SHOW_PLACE_SEARCH = isItinerary && shown.length > 6;
  const normalizedPlaceQuery = placeQuery.trim().toLowerCase();

  const sections = useMemo(() => {
    const base =
      isItinerary && normalizedPlaceQuery
        ? shown.filter((e) => e.name.toLowerCase().includes(normalizedPlaceQuery))
        : shown;
    const sorted = [...base].sort(byPinned);
    if (isItinerary) {
      const groups = groupByDay(sorted.map((e) => ({ day: clean(e.fields.day) })));
      return groups.map((g) => ({ label: g.label, entities: g.indices.map((i) => sorted[i]) }));
    }
    if (statusesFor(type)) {
      return bySection(type, sorted).map((s) => ({ label: s.status.label, entities: s.entities }));
    }
    const open = sorted.filter((e) => e.state?.done !== true);
    const done = sorted.filter((e) => e.state?.done === true);
    return [
      { label: collMeta.sectionLabel, entities: open },
      { label: capitalise(collMeta.doneNoun), entities: done },
    ].filter((s) => s.entities.length > 0);
  }, [shown, type, isItinerary, normalizedPlaceQuery, collMeta.sectionLabel, collMeta.doneNoun]);

  const doneCount = shown.filter((e) => e.state?.done === true).length;
  const selected = entities.find((e) => e.entityKey === selectedKey) ?? null;

  // Tabs only on a leaf, and only when the type asks for more than the list.
  const tabs = hasFolders ? [] : collMeta.tabs.filter((t) => t !== 'sources' || summaries.length > 0);
  const activeTab: CollectionTab = tab && tabs.includes(tab) ? tab : (tabs[0] ?? 'entities');
  // Where the node's own "next up" nudge belongs — Overview when the type
  // has one (it already exists to answer "why are these together", which is
  // exactly what the nudge explains), Entities otherwise. Showing the same
  // card again on Places or on the sources list said the same thing twice.
  const primaryTab: CollectionTab = tabs.includes('overview') ? 'overview' : 'entities';

  /**
   * Optimistic through the store rather than through local component state:
   * the entity list, the section counts and the same entity's controls on a
   * save's own detail screen all read `entity_states`, so one write updates
   * every one of them without any of them knowing about the others.
   */
  const setEntityState = useCallback((entity: CollectionEntityResponse, nextState: Record<string, unknown>) => {
    writeEntityState(entity.entityKey, nextState);
  }, []);
  const cycleStatus = useCallback(
    (entity: CollectionEntityResponse) => {
      const next = nextStatus(type, entity.state);
      if (next) setEntityState(entity, stateForStatus(entity.state, next));
    },
    [type, setEntityState],
  );
  const toggleDone = useCallback(
    (entity: CollectionEntityResponse) => setEntityState(entity, { ...entity.state, done: entity.state?.done !== true }),
    [setEntityState],
  );
  const rate = useCallback(
    (entity: CollectionEntityResponse, rating: number) => setEntityState(entity, { ...entity.state, rating }),
    [setEntityState],
  );
  /**
   * K4: pinning rides `entity_states`' existing `state` jsonb (`state.pinned`)
   * rather than a fourth `collection_overrides` type — see
   * `V14__collection_overrides.sql`'s note on why. Same full-replace write
   * path as status/done/rating.
   */
  const togglePin = useCallback(
    (entity: CollectionEntityResponse) =>
      setEntityState(entity, { ...entity.state, pinned: entity.state?.pinned !== true }),
    [setEntityState],
  );

  // No error branch: reading a derived view of local data cannot fail the way
  // a request could. An empty screen here means the saves behind this node
  // have not synced yet, which is a spinner, not a failure.
  if (!node && entities.length === 0) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  const title = node?.name ?? typeMeta.label;
  const entityCount = node?.entityCount ?? entities.length;

  const summary = [
    hasFolders ? `${subgroups.length} ${collMeta.groupNoun(subgroups.length, depth)}` : null,
    `${entityCount} ${nodeEntityNoun(type, nodeId, entityCount)}`,
    node ? sourceLabel(node.sourceCount) : null,
    doneCount > 0 ? `${doneCount} ${collMeta.doneNoun}` : null,
  ].filter(Boolean);

  const entityList = (
    <>
      {sections.map((section, i) => (
        <Reveal key={section.label} index={i + 1}>
          <SectionLabel>{section.label}</SectionLabel>
          <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
            {section.entities.map((entity, entityIndex) => (
              <EntityRow
                key={entity.entityKey}
                entity={entity}
                type={type}
                number={isWorkout || isItinerary ? entityIndex + 1 : undefined}
                onPress={() => setSelectedKey(entity.entityKey)}
                onCycleStatus={() => cycleStatus(entity)}
                onToggleDone={() => toggleDone(entity)}
              />
            ))}
          </View>
        </Reveal>
      ))}
    </>
  );

  /**
   * The Sources tab: the raw saves, as the rest of the app renders them.
   *
   * Deliberately `SaveCard` rather than the `SourceCard` the overview uses.
   * The two tabs would otherwise be the same list twice — and they have
   * genuinely different jobs: the overview answers "what shape did each
   * source have" (the route, the prescription), while this one answers
   * "which of my saves are these", with the thumbnail, source platform and
   * status the user recognises them by everywhere else.
   */
  const sourceList = (
    <Reveal index={1}>
      <SectionLabel>{collMeta.sourcesLabel}</SectionLabel>
      <View style={{ gap: spacing.smd }}>
        {sources.map((save) => (
          <SaveCard
            key={save.id}
            save={save}
            onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
          />
        ))}
      </View>
    </Reveal>
  );

  /** The overview's un-merged view: each source's own shape, in its own order. */
  const sourceOutlines = (
    <Reveal index={1}>
      <SectionLabel>{collMeta.sourcesLabel}</SectionLabel>
      <View style={{ gap: spacing.sm }}>
        {summaries.map((s) => (
          <SourceCard
            key={s.saveId}
            summary={s}
            onPress={() => router.push({ pathname: '/save/[id]', params: { id: s.saveId } })}
          />
        ))}
      </View>
    </Reveal>
  );

  return (
    <>
      <Screen>
        <Reveal index={0}>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Back"
            onPress={() => router.back()}
            style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginBottom: spacing.md }}
          >
            <Glyph name="chevron" size={14} />
            <AppText variant="caption" tone="muted">
              Back
            </AppText>
          </Touchable>

          {/* A folder shows which collection it belongs to, so "Japan" is
              never adrift — the header is the breadcrumb the stack cannot draw. */}
          {depth > 0 ? (
            <AppText variant="caption" tone="muted" style={{ marginBottom: 2 }}>
              {displayName(type)}
            </AppText>
          ) : null}
          <AppText variant="title" style={{ marginBottom: spacing.xs }}>
            {title}
          </AppText>
          <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
            {summary.join(' · ')}
          </AppText>

          {/* The node's own action, where its type has one. Above the tabs
              because it applies to the whole node, not to one tab's content. */}
          {collMeta.action && !hasFolders && entities.length > 0 ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel={collMeta.action.label}
              onPress={() => router.push({ pathname: '/session/[nodeId]', params: { nodeId } })}
              haptic="medium"
              weight="card"
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                gap: spacing.xs,
                paddingVertical: spacing.smd,
                borderRadius: 100,
                backgroundColor: palette.accent,
                marginBottom: spacing.md,
              }}
            >
              <Glyph name="activity" size={16} weight={2} color={palette.background} />
              <AppText variant="label" style={{ color: palette.background }}>
                {collMeta.action.label}
              </AppText>
            </Touchable>
          ) : null}

          {tabs.length > 1 ? (
            <View style={{ marginBottom: spacing.lg }}>
              <Segmented
                options={tabs.map((t) => ({
                  value: t,
                  label:
                    t === 'entities'
                      ? collMeta.sectionLabel
                      : t === 'sources'
                        ? collMeta.sourcesLabel
                        : 'Overview',
                }))}
                value={activeTab}
                onChange={setTab}
              />
            </View>
          ) : (
            <View style={{ marginBottom: spacing.lg }} />
          )}
        </Reveal>

        {/* This node's own decide card, above the list it decides over — one
            grounded pick, never a generated one, and gone entirely when the
            type's own threshold (`@/collections/nextAction`) is not met.
            Shown once, on `primaryTab` only — repeating it on every tab is
            what made Places and the sources list feel templated rather than
            focused on their own job. */}
        {nextAction && activeTab === primaryTab ? (
          <Reveal index={1}>
            <NextActionCard
              action={nextAction}
              label={NEXT_ACTION_LABELS[type] ?? 'Next up'}
              onPrimary={() => {
                if (nextAction.entityKey) {
                  setSelectedKey(nextAction.entityKey);
                } else {
                  setTab('entities');
                }
              }}
            />
          </Reveal>
        ) : null}

        {/* Workout only: this split's load at a glance, before the exercise
            list — data and derived understanding, per the standing hierarchy,
            never a generated suggestion. */}
        {isWorkout && !hasFolders && (workoutSummary || splitMuscleNames.length > 0) ? (
          <Reveal index={1}>
            <Card style={{ marginBottom: spacing.lg }}>
              {splitMuscleNames.length > 0 ? (
                <View
                  style={{
                    flexDirection: 'row',
                    flexWrap: 'wrap',
                    gap: spacing.xs,
                    marginBottom: workoutSummary ? spacing.sm : 0,
                  }}
                >
                  {splitMuscleNames.map((muscle) => (
                    <View
                      key={muscle}
                      style={{
                        paddingVertical: 4,
                        paddingHorizontal: spacing.sm,
                        borderRadius: 100,
                        backgroundColor: palette.surfaceVariant,
                      }}
                    >
                      <AppText variant="caption">{muscle}</AppText>
                    </View>
                  ))}
                </View>
              ) : null}
              {workoutSummary ? (
                <AppText variant="caption" tone="muted">
                  {`${workoutSummary.totalSets} sets across ${workoutSummary.countedExercises} exercise${workoutSummary.countedExercises === 1 ? '' : 's'} · ~${workoutSummary.estimatedMinutes} min (est.)`}
                </AppText>
              ) : null}
            </Card>
          </Reveal>
        ) : null}

        {entities.length === 0 ? (
          <Reveal index={1}>
            <Card>
              <AppText variant="caption" tone="muted">
                Nothing merged into this collection yet.
              </AppText>
            </Card>
          </Reveal>
        ) : hasFolders ? (
          <>
            <Reveal index={1}>
              <SectionLabel>{capitalise(collMeta.groupNoun(subgroups.length, depth))}</SectionLabel>
              <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
                {subgroups.map((child) => (
                  <SubgroupRow
                    key={child.id}
                    node={child}
                    type={type}
                    muscles={
                      isWorkout
                        ? musclesInSplit(
                            rawMuscleGroups(
                              child.saveIds
                                .map((id) => sourceById.get(id))
                                .filter((s): s is SaveResponse => !!s),
                            ),
                            child.name,
                          )
                        : undefined
                    }
                    onPress={() => router.push({ pathname: '/collection/[type]', params: { type: child.id } })}
                  />
                ))}
              </View>
            </Reveal>
            {/* Entities the folders did not claim still need somewhere to be —
                otherwise a place whose destination was too rare to earn a
                folder would vanish from the collection entirely. */}
            {entityList}
          </>
        ) : activeTab === 'sources' ? (
          sourceList
        ) : activeTab === 'overview' ? (
          <>
            {sourceOutlines}
            <Reveal index={2} style={{ marginTop: spacing.xxl - 2 }}>
              <SectionLabel>{`All ${nodeEntityNoun(type, nodeId, entityCount)}`}</SectionLabel>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm }}>
                {shown.map((entity) => (
                  <Touchable
                    key={entity.entityKey}
                    accessibilityRole="button"
                    accessibilityLabel={`${entity.name}, open`}
                    onPress={() => setSelectedKey(entity.entityKey)}
                    haptic="selection"
                    style={{
                      paddingVertical: 6,
                      paddingHorizontal: spacing.smd,
                      borderRadius: 100,
                      borderWidth: 1,
                      borderColor: palette.border,
                      backgroundColor: palette.surface,
                    }}
                  >
                    <AppText variant="caption">
                      {entity.name}
                      {entity.sourceCount > 1 ? ` · ${entity.sourceCount}` : ''}
                    </AppText>
                  </Touchable>
                ))}
              </View>
            </Reveal>
          </>
        ) : (
          <>
            {SHOW_PLACE_SEARCH ? (
              <Reveal index={1}>
                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.smd,
                    backgroundColor: palette.surface,
                    borderWidth: 1,
                    borderColor: palette.border,
                    borderRadius: 100,
                    paddingVertical: spacing.sm,
                    paddingHorizontal: spacing.lg,
                    marginBottom: spacing.lg,
                  }}
                >
                  <Glyph name="search" size={16} weight={2} color={palette.textMuted} />
                  <TextInput
                    value={placeQuery}
                    onChangeText={setPlaceQuery}
                    placeholder={`Search ${collMeta.entityNoun(2)}…`}
                    placeholderTextColor={palette.textFaint}
                    accessibilityLabel={`Search ${collMeta.entityNoun(2)}`}
                    style={{ flex: 1, paddingVertical: 4, fontSize: 14, color: palette.text }}
                  />
                  {placeQuery ? (
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel="Clear search"
                      onPress={() => setPlaceQuery('')}
                      haptic="selection"
                    >
                      <AppText variant="caption" tone="muted">
                        Clear
                      </AppText>
                    </Touchable>
                  ) : null}
                </View>
              </Reveal>
            ) : null}
            {normalizedPlaceQuery && sections.length === 0 ? (
              <Reveal index={2}>
                <Card>
                  <AppText variant="caption" tone="muted">
                    No {collMeta.entityNoun(2)} match “{placeQuery.trim()}”.
                  </AppText>
                </Card>
              </Reveal>
            ) : (
              entityList
            )}
          </>
        )}
      </Screen>

      {selected ? (
        <EntityDetailSheet
          entity={selected}
          type={type}
          sourceTitles={sourceTitles}
          onClose={() => setSelectedKey(null)}
          onCycleStatus={() => cycleStatus(selected)}
          onToggleDone={() => toggleDone(selected)}
          onRate={(rating) => rate(selected, rating)}
          onTogglePin={() => togglePin(selected)}
          onOpenSave={(saveId) => {
            setSelectedKey(null);
            router.push({ pathname: '/save/[id]', params: { id: saveId } });
          }}
        />
      ) : null}
    </>
  );
}
