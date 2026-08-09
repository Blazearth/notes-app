import { useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, View } from 'react-native';

import type { CollectionNodeResponse, SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { CollectionCard } from '@/components/CollectionCard';
import { Glyph, type GlyphName } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readCollections } from '@/local/derived';
import { saveTitle, STATUS_LABELS } from '@/saves/format';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { writeSaveFlags } from '@/local/writes';
import { useSaves } from '@/saves/SavesProvider';
import { useTheme } from '@/theme/ThemeProvider';

const ALL = 'All';
const FAVORITES = 'Favorites';
const ARCHIVED = 'Archived';

type SortKey = 'recent' | 'alphabetical';

/** Stable identity for the "nothing derived yet" case — see `useLiveValue`. */
const EMPTY_COLLECTIONS: CollectionNodeResponse[] = [];

const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'recent', label: 'Recently added' },
  { key: 'alphabetical', label: 'A–Z' },
];

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
  const { palette, layout, spacing } = useTheme();
  const { saves, status, error, refresh, refreshing } = useSaves();
  const router = useRouter();
  const [filter, setFilter] = useState<string>(ALL);
  const [sortBy, setSortBy] = useState<SortKey>('recent');
  const [sortMenuOpen, setSortMenuOpen] = useState(false);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

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

  const filters = [
    ALL,
    ...counts.map(([type]) => type),
    ...(favoriteCount > 0 ? [FAVORITES] : []),
    ...(archivedCount > 0 ? [ARCHIVED] : []),
  ];

  const filtered: SaveResponse[] = useMemo(() => {
    // In the ALL view, text saves are shown in the Handnotes section above,
    // not in the 'Everything' list, to avoid duplication.
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
              <HeaderAction glyph="search" label="Search saves" onPress={() => router.push('/search')} />
              <HeaderAction
                glyph="filter"
                label="Sort"
                active={sortMenuOpen}
                onPress={() => setSortMenuOpen((v) => !v)}
              />
            </View>
          </>
        )}
      </Reveal>

      {sortMenuOpen && !selectionMode ? (
        <Reveal
          index={1}
          style={{ flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.lg }}
        >
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
            <AppText variant="caption" tone="muted">
              Tap + and paste a link, or share something into Weavr from another app.
            </AppText>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && saves.length > 0 ? (
        <>
          {filters.length > 1 ? (
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
                    tint={
                      option !== ALL && option !== FAVORITES && option !== ARCHIVED
                        ? saveTypeMeta(option).color
                        : undefined
                    }
                    onPress={() => setFilter(option)}
                  />
                ))}
              </ScrollView>
            </Reveal>
          ) : null}

          {collections.length > 0 && filter === ALL ? (
            <Reveal index={3}>
              <SectionLabel>Collections</SectionLabel>
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
                      onArchive={() => void setFlag(save, { archived: !save.archived })}
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
            <SectionLabel>{filter === ALL ? 'Everything' : labelFor(filter)}</SectionLabel>
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
                    onArchive={() => setFlag(save, { archived: !save.archived })}
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
                        ? 'Swipe left on a save to archive it.'
                        : `Saves land in ${labelFor(filter)} once the pipeline classifies them.`}
                  </AppText>
                </Card>
              </Reveal>
            )}
          </View>
        </>
      ) : null}
    </Screen>
  );
}
