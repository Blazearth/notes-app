import { useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, View } from 'react-native';

import type { CollectionNodeResponse, SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { CollectionCard } from '@/components/CollectionCard';
import { ConfirmSheet } from '@/components/ConfirmSheet';
import { Glyph, type GlyphName } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readCollections } from '@/local/derived';
import { saveTitle, STATUS_LABELS, TRY_IT_EXAMPLES } from '@/saves/format';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { writeSaveFlags, writeDeleteSave } from '@/local/writes';
import { useSaves } from '@/saves/SavesProvider';
import { useTheme } from '@/theme/ThemeProvider';

const ALL = 'All';
const FAVORITES = 'Favorites';
const ARCHIVED = 'Archived';

type SortKey = 'recent' | 'alphabetical';

/**
 * Which axis the type/filter chip row is narrowed to. Purely a display
 * filter over the chips already computed below — it never changes what
 * `filter` itself can be, so switching axes can leave the current filter
 * chip hidden (handled by resetting to ALL when that happens).
 */
type Dimension = 'all' | 'collections' | 'types';

const DIMENSIONS: { key: Dimension; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'collections', label: 'Collections' },
  { key: 'types', label: 'Types' },
];

/** Stable identity for the "nothing derived yet" case — see `useLiveValue`. */
const EMPTY_COLLECTIONS: CollectionNodeResponse[] = [];

const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'recent', label: 'Recently added' },
  { key: 'alphabetical', label: 'A–Z' },
];

const AI_ORGANIZED_TITLE = 'Why these are grouped';
const AI_ORGANIZED_MESSAGE =
  'Weavr automatically grouped your saves based on their content and relationships.';

function labelFor(filter: string): string {
  if (filter === ALL || filter === FAVORITES || filter === ARCHIVED) return filter;
  return saveTypeMeta(filter).label;
}

function HeaderAction({
  glyph,
  label,
  active,
  onPress,
}: {
  glyph: GlyphName;
  label: string;
  active?: boolean;
  onPress?: () => void;
}) {
  const { palette, radius, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      weight="tile"
      onPress={onPress}
      haptic={onPress ? 'selection' : null}
      style={{
        width: 36,
        height: 36,
        borderRadius: radius.sm,
        backgroundColor: active ? palette.text : palette.surface,
        borderWidth: 1,
        borderColor: active ? 'transparent' : palette.border,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Glyph name={glyph} size={icon.sm} color={active ? palette.background : undefined} />
    </Touchable>
  );
}

/** A knowledge type, as an icon tile in the horizontally-scrolling "By type" row. */
function TypeTile({ type, count, onPress }: { type: string; count: number; onPress: () => void }) {
  const { palette, radius, spacing } = useTheme();
  const meta = saveTypeMeta(type);
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={`${meta.label}, ${count} ${count === 1 ? 'save' : 'saves'}`}
      onPress={onPress}
      haptic="selection"
      weight="card"
      style={{
        width: 96,
        borderRadius: radius.md,
        borderWidth: 1,
        borderColor: palette.border,
        backgroundColor: palette.surface,
        padding: spacing.smd,
        gap: spacing.sm,
      }}
    >
      <View
        style={{
          width: 32,
          height: 32,
          borderRadius: radius.sm,
          backgroundColor: `${meta.color}26`,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Glyph name={meta.glyph} size={16} weight={2} color={meta.color} />
      </View>
      <View>
        <AppText variant="cardTitle" style={{ fontSize: 12 }} numberOfLines={1}>
          {meta.label}
        </AppText>
        <AppText variant="caption" tone="muted" style={{ fontSize: 11, marginTop: 1 }}>
          {count} {count === 1 ? 'save' : 'saves'}
        </AppText>
      </View>
    </Touchable>
  );
}

/**
 * Everything the user has saved, grouped by what the pipeline decided it was.
 *
 * Reads the same `SavesProvider` the Home feed does rather than fetching again:
 * one list, one refresh, and a save created in Capture appears in both without
 * either screen knowing about the other.
 */
export function LibraryScreen() {
  const { palette, layout, radius, spacing, icon } = useTheme();
  const { saves, status, error, refresh, refreshing } = useSaves();
  const router = useRouter();
  const [filter, setFilter] = useState<string>(ALL);
  const [dimension, setDimension] = useState<Dimension>('all');
  const [sortBy, setSortBy] = useState<SortKey>('recent');
  const [sortMenuOpen, setSortMenuOpen] = useState(false);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleteTarget, setDeleteTarget] = useState<SaveResponse | null>(null);
  const [bulkDeleteConfirm, setBulkDeleteConfirm] = useState(false);
  const [showAiOrganizedInfo, setShowAiOrganizedInfo] = useState(false);

  // K3: the Library's top level for entity-bearing types (recommendation_list,
  // itinerary, checklist) renders collections, not save rows — see
  // docs/knowledge-collections.md.
  //
  // No longer `GET /v1/collections`. It is a *different derived view of the
  // same saves*, and both the saves and the K2 entity state that gives it
  // `doneCount` are already local — so it is computed from them, by the same
  // merge core the server runs (`@/collections/merge`), and updates the instant
  // a save or an entity state changes rather than on the next refetch.
  const collections = useLiveValue<CollectionNodeResponse[]>(DERIVED_TABLES, readCollections, EMPTY_COLLECTIONS);
  const onRefresh = useCallback(() => {
    void refresh();
  }, [refresh]);

  // A type only leaves the "By type" shortcut row once it actually produced a
  // collection — a recommendation_list save with no usable items (all
  // `[unclear]` names) produces no node at all, so it keeps its ordinary
  // type tile like any other.
  const entityBearingTypes = useMemo(() => new Set(collections.map((c) => c.id)), [collections]);

  // Handnotes — manually typed text saves — get their own named section so
  // they are always visible at a glance without the user having to know to
  // filter by 'other'. Non-archived only: archived handnotes stay reachable
  // via the Archived filter just like any other save.
  const handnotes = useMemo(
    () => saves.filter((s) => s.sourceType === 'text' && !s.archived),
    [saves],
  );

  // Archived saves sit out of the ordinary views entirely — an archive is
  // only useful if it actually gets things out of the way. The `Archived`
  // filter is the one place they're still reachable.
  //
  // Saves whose type produced a collection are *not* excluded any more, and
  // that reversal is deliberate. Collections are derived organisation;
  // Everything is the raw source history, and it has to be complete to be the
  // safety net it exists to be — if the model files something wrongly, the
  // save the user actually made must still be somewhere they can find it.
  // Duplication between the two views is the point, not an accident.
  //
  // Text saves are still excluded when the filter is ALL, because the
  // dedicated Handnotes section above shows exactly them; a type filter still
  // includes them if the pipeline classified them as that type.
  const activeSaves = useMemo(() => saves.filter((s) => !s.archived), [saves]);

  // Counts drive the filter chips, so a type with nothing in it never appears
  // as an empty option the user can select into a dead end. Every type present
  // gets a chip, including ones with a collection — the chips narrow
  // Everything, which is the complete list, so a type missing from them would
  // be unreachable there.
  const counts = useMemo(() => {
    const tally = new Map<string, number>();
    for (const save of activeSaves) {
      if (save.status !== 'ready' || !save.knowledgeType) continue;
      tally.set(save.knowledgeType, (tally.get(save.knowledgeType) ?? 0) + 1);
    }
    return [...tally.entries()].sort((a, b) => b[1] - a[1]);
  }, [activeSaves]);

  // The "By type" tiles are a different job from the chips: they are an
  // organisational entry point, and a type with a collection already has a
  // better one directly above. Two tiles into the same saves would just
  // compete — the reason this row stays compact rather than becoming a
  // second grid of big cards.
  const tileCounts = useMemo(
    () => counts.filter(([type]) => !entityBearingTypes.has(type)),
    [counts, entityBearingTypes],
  );

  const favoriteCount = useMemo(() => activeSaves.filter((s) => s.favorite).length, [activeSaves]);
  const archivedCount = useMemo(() => saves.filter((s) => s.archived).length, [saves]);

  // The All/Collections/Types split is only worth showing once both axes
  // actually have something in them — a library with no collections yet has
  // nothing for "Collections" to narrow to, so the split would be a dead
  // toggle rather than a useful one.
  const dimensionAvailable = entityBearingTypes.size > 0 && tileCounts.length > 0;

  // Favorites/Archived are status filters, not type-organised ones — they
  // live in the sort popover (below) rather than this row, which is purely
  // "what kind of thing is this" at every dimension. That is also why this
  // row is never shown at all when the View is 'All' and Collections exist:
  // there is nothing left for it to narrow that Collections/By type/Recent
  // Saves don't already offer as their own entry point.
  const filters =
    dimensionAvailable && dimension === 'collections'
      ? [ALL, ...counts.filter(([type]) => entityBearingTypes.has(type)).map(([type]) => type)]
      : dimensionAvailable && dimension === 'types'
        ? [ALL, ...counts.filter(([type]) => !entityBearingTypes.has(type)).map(([type]) => type)]
        : [ALL, ...counts.map(([type]) => type)];

  // The row itself only renders when it has something to say: once
  // Collections exist, 'All' has its own sections below and showing a second
  // tab-like row under it was exactly the confusion this redesign removes.
  // Without any Collections yet, there's no dimension row above to confuse it
  // with, so the type chips stay as the one navigation row.
  const showFilterRow = (!dimensionAvailable || dimension !== 'all') && filters.length > 1;

  const filtered: SaveResponse[] = useMemo(() => {
    // In the ALL view, text saves are shown in the Handnotes section above,
    // not in the 'Recent Saves' list, to avoid duplication.
    const base = filter === ALL ? activeSaves.filter((s) => s.sourceType !== 'text') : activeSaves;
    if (filter === ALL) return base;
    if (filter === FAVORITES) return activeSaves.filter((s) => s.favorite);
    if (filter === ARCHIVED) return saves.filter((s) => s.archived);
    return activeSaves.filter((s) => s.knowledgeType === filter);
  }, [activeSaves, saves, filter]);

  const visible = useMemo(() => {
    if (sortBy === 'alphabetical') {
      return [...filtered].sort((a, b) => saveTitle(a).localeCompare(saveTitle(b)));
    }
    return filtered; // already newest-first from the server
  }, [filtered, sortBy]);

  const toggleSelected = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const exitSelection = useCallback(() => {
    setSelectionMode(false);
    setSelectedIds(new Set());
  }, []);

  const handleLongPress = useCallback(
    (id: string) => {
      setSelectionMode(true);
      setSelectedIds(new Set([id]));
    },
    [],
  );

  const handleCardPress = useCallback(
    (save: SaveResponse) => {
      if (selectionMode) {
        toggleSelected(save.id);
        return;
      }
      router.push({ pathname: '/save/[id]', params: { id: save.id } });
    },
    [selectionMode, toggleSelected, router],
  );

  /**
   * Local first, queued second — see `@/local/writes`. There is no revert
   * branch here any more and that is the point: a swipe that failed used to be
   * silently undone, which is indistinguishable from "the swipe never
   * registered". The write is now retried until it lands, or surfaced if the
   * server rejects it outright.
   */
  const setFlag = useCallback(
    (save: SaveResponse, changes: { favorite?: boolean; archived?: boolean }) => {
      writeSaveFlags(save.id, changes);
    },
    [],
  );

  const bulkApply = useCallback(
    (changes: { favorite?: boolean; archived?: boolean }) => {
      const ids = [...selectedIds];
      exitSelection();
      // No `Promise.all` and no partial-success caveat: each id is its own
      // queued write, ordered per save and independent across saves, so one
      // rejection can neither roll back nor hold up the rest.
      for (const id of ids) writeSaveFlags(id, changes);
    },
    [selectedIds, exitSelection],
  );

  const confirmDelete = useCallback((save: SaveResponse) => setDeleteTarget(save), []);

  const bulkDelete = useCallback(() => setBulkDeleteConfirm(true), []);

  return (
    <Screen
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          tintColor={palette.accent}
          colors={[palette.accent]}
          progressBackgroundColor={palette.surface}
        />
      }
    >
      <Reveal
        index={0}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.lg,
        }}
      >
        {selectionMode ? (
          <>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
              <Touchable accessibilityRole="button" onPress={exitSelection} haptic="light">
                <Glyph name="close" size={20} />
              </Touchable>
              <AppText variant="display" style={{ fontSize: 22 }}>
                {selectedIds.size} selected
              </AppText>
            </View>
            <View style={{ flexDirection: 'row', gap: spacing.sm }}>
              <HeaderAction
                glyph="heart"
                label="Favorite selected"
                onPress={() => bulkApply({ favorite: true })}
              />
              <HeaderAction
                glyph="archive"
                label="Archive selected"
                onPress={() => bulkApply({ archived: true })}
              />
              <HeaderAction
                glyph="close"
                label="Delete selected"
                onPress={bulkDelete}
              />
            </View>
          </>
        ) : (
          <>
            <View>
              <AppText variant="display">Library</AppText>
              {status === 'ready' && saves.length > 0 ? (
                <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
                  {saves.length} saved {saves.length === 1 ? 'item' : 'items'}
                </AppText>
              ) : null}
            </View>
            <View style={{ flexDirection: 'row', gap: spacing.sm }}>
              <HeaderAction
                glyph="sort"
                label="Sort"
                active={sortMenuOpen}
                onPress={() => setSortMenuOpen((v) => !v)}
              />
            </View>
          </>
        )}
      </Reveal>

      {/* Same copy as Home and Search — one wording for the app's one hybrid
          retrieval mechanism, not three that drift. */}
      {!selectionMode ? (
        <Reveal index={1}>
          <Touchable
            accessibilityRole="search"
            accessibilityLabel="Search your saves"
            onPress={() => router.push('/search')}
            haptic="selection"
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing.smd,
              backgroundColor: palette.surface,
              borderWidth: 1,
              borderColor: palette.border,
              borderRadius: radius.pill,
              paddingVertical: spacing.md,
              paddingHorizontal: spacing.lg,
              marginBottom: spacing.lg,
            }}
          >
            <Glyph name="search" size={16} weight={2} />
            <AppText tone="muted" style={{ fontSize: 14 }}>
              Ask or find anything…
            </AppText>
          </Touchable>
        </Reveal>
      ) : null}

      {sortMenuOpen && !selectionMode ? (
        <Reveal index={2} style={{ marginBottom: spacing.lg }}>
          <View style={{ flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' }}>
            {SORT_OPTIONS.map((opt) => (
              <Chip
                key={opt.key}
                label={opt.label}
                selected={sortBy === opt.key}
                onPress={() => {
                  setSortBy(opt.key);
                  setSortMenuOpen(false);
                }}
              />
            ))}
          </View>
          {favoriteCount > 0 || archivedCount > 0 ? (
            <View style={{ flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap', marginTop: spacing.sm }}>
              {favoriteCount > 0 ? (
                <Chip
                  label={FAVORITES}
                  selected={filter === FAVORITES}
                  onPress={() => {
                    setFilter((f) => (f === FAVORITES ? ALL : FAVORITES));
                    setSortMenuOpen(false);
                  }}
                />
              ) : null}
              {archivedCount > 0 ? (
                <Chip
                  label={ARCHIVED}
                  selected={filter === ARCHIVED}
                  onPress={() => {
                    setFilter((f) => (f === ARCHIVED ? ALL : ARCHIVED));
                    setSortMenuOpen(false);
                  }}
                />
              ) : null}
            </View>
          ) : null}
        </Reveal>
      ) : null}

      {status === 'loading' ? (
        <View style={{ paddingVertical: spacing.xxl * 2, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {status === 'error' ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              {error?.kind === 'network' ? "Can't reach Weavr" : 'Could not load your library'}
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              {error?.message}
            </AppText>
            <Touchable accessibilityRole="button" onPress={() => void refresh()} haptic="medium">
              <AppText variant="label" tone="accent">
                Try again
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && saves.length === 0 ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Your library is empty
            </AppText>
            <AppText variant="bodySmall" tone="muted" style={{ marginBottom: spacing.md }}>
              Paste a link, or share anything into Weavr from another app — it'll organize it for you.
            </AppText>
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Add something"
              onPress={() => router.push('/capture')}
              haptic="medium"
              weight="tile"
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                gap: spacing.smd,
                paddingVertical: spacing.md,
                borderRadius: radius.sm,
                backgroundColor: palette.accent,
                marginBottom: spacing.lg,
              }}
            >
              <Glyph name="plus" size={icon.sm} weight={2} color={palette.onAccent} />
              <AppText variant="label" style={{ color: palette.onAccent }}>
                Add something
              </AppText>
            </Touchable>

            <SectionLabel>Try it with</SectionLabel>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm }}>
              {TRY_IT_EXAMPLES.map((label) => (
                <Chip key={label} label={label} onPress={() => router.push('/capture')} />
              ))}
            </View>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && saves.length > 0 ? (
        <>
          {dimensionAvailable ? (
            <Reveal
              index={2}
              style={{ flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm }}
            >
              {DIMENSIONS.map((d) => (
                <Chip
                  key={d.key}
                  label={d.label}
                  selected={dimension === d.key}
                  onPress={() => {
                    setDimension(d.key);
                    setFilter(ALL);
                  }}
                />
              ))}
            </Reveal>
          ) : null}

          {showFilterRow ? (
            <Reveal index={2}>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                style={{ marginHorizontal: -layout.screenGutter, marginBottom: spacing.lg + 2 }}
                contentContainerStyle={{
                  paddingHorizontal: layout.screenGutter,
                  gap: spacing.sm,
                }}
              >
                {filters.map((option) => (
                  <Chip
                    key={option}
                    label={labelFor(option)}
                    selected={option === filter}
                    tint={option !== ALL ? saveTypeMeta(option).color : undefined}
                    onPress={() => setFilter(option)}
                  />
                ))}
              </ScrollView>
            </Reveal>
          ) : null}

          {collections.length > 0 && filter === ALL ? (
            <Reveal index={3}>
              <SectionLabel
                trailing={
                  <Touchable
                    accessibilityRole="button"
                    accessibilityLabel="Why these are grouped"
                    onPress={() => setShowAiOrganizedInfo(true)}
                    haptic="light"
                  >
                    <AppText variant="caption" tone="muted" style={{ fontSize: 11, textDecorationLine: 'underline' }}>
                      AI-organized
                    </AppText>
                  </Touchable>
                }
              >
                Collections
              </SectionLabel>
              <View style={{ gap: spacing.smd, marginBottom: spacing.xxl - 2 }}>
                {collections.map((node) => (
                  <CollectionCard
                    key={node.id}
                    node={node}
                    type={node.id}
                    onPress={() => router.push({ pathname: '/collection/[type]', params: { type: node.id } })}
                  />
                ))}
              </View>
            </Reveal>
          ) : null}

          {handnotes.length > 0 && filter === ALL ? (
            <Reveal index={3}>
              <SectionLabel>Handnotes</SectionLabel>
              <View style={{ gap: spacing.smd, marginBottom: spacing.xxl - 2 }}>
                {handnotes.map((save, i) => (
                  <Reveal key={`handnote-${save.id}`} index={i}>
                    <SaveCard
                      save={save}
                      selectionMode={selectionMode}
                      selected={selectedIds.has(save.id)}
                      onPress={() => handleCardPress(save)}
                      onLongPress={() => handleLongPress(save.id)}
                      onFavorite={() => void setFlag(save, { favorite: !save.favorite })}
                      onDelete={() => confirmDelete(save)}
                      trailing={
                        save.status === 'ready' ? undefined : (
                          <AppText variant="caption" tone="muted" style={{ fontSize: 10 }}>
                            {STATUS_LABELS[save.status]}
                          </AppText>
                        )
                      }
                    />
                  </Reveal>
                ))}
              </View>
            </Reveal>
          ) : null}

          {tileCounts.length > 0 && filter === ALL ? (
            <Reveal index={4}>
              <SectionLabel>By type</SectionLabel>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                style={{ marginHorizontal: -layout.screenGutter, marginBottom: spacing.xxl - 2 }}
                contentContainerStyle={{ paddingHorizontal: layout.screenGutter, gap: spacing.sm }}
              >
                {tileCounts.map(([type, count]) => (
                  <TypeTile key={type} type={type} count={count} onPress={() => setFilter(type)} />
                ))}
              </ScrollView>
            </Reveal>
          ) : null}

          <Reveal index={5}>
            <SectionLabel>{filter === ALL ? 'Recent Saves' : labelFor(filter)}</SectionLabel>
          </Reveal>
          <View style={{ gap: spacing.smd }}>
            {visible.length > 0 ? (
              visible.map((save, i) => (
                // Keyed by filter as well as id, so switching filters remounts
                // the rows and they animate in. Without the filter in the key,
                // React reuses the survivors and a filter change lands silently.
                <Reveal key={`${filter}-${save.id}`} index={i}>
                  <SaveCard
                    save={save}
                    selectionMode={selectionMode}
                    selected={selectedIds.has(save.id)}
                    onPress={() => handleCardPress(save)}
                    onLongPress={() => handleLongPress(save.id)}
                    onFavorite={() => setFlag(save, { favorite: !save.favorite })}
                    onDelete={() => confirmDelete(save)}
                    trailing={
                      save.status === 'ready' ? undefined : (
                        <AppText variant="caption" tone="muted" style={{ fontSize: 10 }}>
                          {STATUS_LABELS[save.status]}
                        </AppText>
                      )
                    }
                  />
                </Reveal>
              ))
            ) : (
              <Reveal key={`${filter}-empty`}>
                <Card>
                  <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
                    Nothing here yet
                  </AppText>
                  <AppText variant="caption" tone="muted">
                    {filter === FAVORITES
                      ? 'Swipe right on a save, or long-press to select several, to favorite it.'
                      : filter === ARCHIVED
                        ? 'Long-press a save to select it, then Archive selected.'
                        : `Saves land in ${labelFor(filter)} once the pipeline classifies them.`}
                  </AppText>
                </Card>
              </Reveal>
            )}
          </View>
        </>
      ) : null}

      <ConfirmSheet
        visible={deleteTarget !== null}
        title="Delete this save?"
        itemLabel={deleteTarget ? saveTitle(deleteTarget) : undefined}
        message="This removes it from your library. This can't be undone."
        confirmLabel="Delete save"
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget) writeDeleteSave(deleteTarget.id);
          setDeleteTarget(null);
        }}
      />

      <ConfirmSheet
        visible={bulkDeleteConfirm}
        title={`Delete ${selectedIds.size} save${selectedIds.size > 1 ? 's' : ''}?`}
        message="This removes them from your library. This can't be undone."
        confirmLabel="Delete saves"
        onCancel={() => setBulkDeleteConfirm(false)}
        onConfirm={() => {
          const ids = [...selectedIds];
          exitSelection();
          setBulkDeleteConfirm(false);
          for (const id of ids) writeDeleteSave(id);
        }}
      />

      <ConfirmSheet
        visible={showAiOrganizedInfo}
        title={AI_ORGANIZED_TITLE}
        message={AI_ORGANIZED_MESSAGE}
        confirmLabel="Got it"
        destructive={false}
        onConfirm={() => setShowAiOrganizedInfo(false)}
      />
    </Screen>
  );
}
