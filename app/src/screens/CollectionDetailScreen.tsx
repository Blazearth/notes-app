import { useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ActivityIndicator, Image, Modal, ScrollView, View } from 'react-native';

import type { CollectionEntityResponse, CollectionNodeResponse } from '@/api/types';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readCollectionEntities, readCollections } from '@/local/derived';
import { writeEntityState } from '@/local/writes';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { collectionTypeMeta } from '@/collections/collectionMeta';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for the "nothing derived yet" case — see `useLiveValue`. */
const EMPTY_ENTITIES: CollectionEntityResponse[] = [];

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== '[unclear]' ? trimmed : null;
}

/** One entity, as a row — poster/tile, name, rolled-up meta, source count. Mirrors `SubgroupRow`'s density. */
function EntityRow({
  entity,
  type,
  onPress,
}: {
  entity: CollectionEntityResponse;
  type: string;
  onPress: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const typeMeta = saveTypeMeta(type);
  const posterUrl = clean(entity.fields.posterUrl);
  const meta = [clean(entity.kind), clean(entity.fields.year)].filter((v): v is string => !!v).join(' · ');
  const done = entity.state?.done === true;
  const pinned = entity.state?.pinned === true;

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${entity.name}, ${entity.sourceCount} ${entity.sourceCount === 1 ? 'source' : 'sources'}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        {posterUrl ? (
          <Image source={{ uri: posterUrl }} style={{ width: 36, height: 54, borderRadius: radius.sm }} resizeMode="cover" />
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
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
            {[meta, `${entity.sourceCount} ${entity.sourceCount === 1 ? 'source' : 'sources'}`]
              .filter(Boolean)
              .join(' · ')}
          </AppText>
        </View>
        {pinned ? <Glyph name="bookmark" size={14} weight={2} color={palette.accent} /> : null}
        {done ? <Glyph name="checkSquare" size={16} color={palette.accent} /> : null}
      </Touchable>
    </Card>
  );
}

/**
 * The entity detail sheet — every source's reason attributed to its own
 * source, and a rail back to each original save (the provenance view, per
 * `docs/knowledge-collections.md`). A plain `Modal` rather than the shared
 * `Sheet` chrome: `Sheet` drives its dismissal through `router.back()`, which
 * assumes it is its own route — this opens from data already held by the
 * collection screen, so a route (and the entity-key path-encoding it would
 * need) buys nothing.
 */
function EntityDetailSheet({
  entity,
  type,
  onClose,
  onOpenSave,
  onToggleDone,
  onRate,
  onTogglePin,
}: {
  entity: CollectionEntityResponse;
  type: string;
  onClose: () => void;
  onOpenSave: (saveId: string) => void;
  onToggleDone: () => void;
  onRate: (rating: number) => void;
  onTogglePin: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const collMeta = collectionTypeMeta(type);
  const done = entity.state?.done === true;
  const pinned = entity.state?.pinned === true;
  const rating = typeof entity.state?.rating === 'number' ? entity.state.rating : 0;

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
            maxHeight: '80%',
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
              {[clean(entity.kind), clean(entity.fields.year)].filter(Boolean).join(' · ') || 'No details yet'}
            </AppText>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginBottom: spacing.xl }}>
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
                  {done ? `${collMeta.doneNoun[0].toUpperCase()}${collMeta.doneNoun.slice(1)}` : `Mark ${collMeta.doneNoun}`}
                </AppText>
              </Touchable>
              {collMeta.ratable ? (
                <View style={{ flexDirection: 'row', gap: 2 }}>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <Touchable
                      key={n}
                      accessibilityRole="button"
                      accessibilityLabel={`Rate ${n} of 5`}
                      onPress={() => onRate(n)}
                      haptic="selection"
                    >
                      <Glyph name="star" size={14} color={n <= rating ? palette.accent : palette.textFaint} />
                    </Touchable>
                  ))}
                </View>
              ) : null}
            </View>

            <SectionLabel>{entity.sourceCount === 1 ? 'Source' : `Sources (${entity.sourceCount})`}</SectionLabel>
            <View style={{ gap: spacing.smd, marginBottom: spacing.lg }}>
              {entity.sources.map((source) => {
                const reason = clean(source.item.reason);
                return (
                  <Card key={source.saveId} radius={radius.md}>
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
 * The collections-first Library screen (K3) — a merged entity list for one
 * type, sectioned by K2 state rather than presented as flat save rows.
 */
export function CollectionDetailScreen({ type }: { type: string }) {
  const { palette, spacing } = useTheme();
  const router = useRouter();

  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  // Both derived from the local store by the same merge core the server runs
  // (`@/collections/merge` + `@/local/derived`), so there is no fetch and no
  // error path: a collection that does not exist locally is one whose saves
  // have not synced yet, not a failed request.
  const node = useLiveValue<CollectionNodeResponse | null>(
    DERIVED_TABLES,
    async (store) => (await readCollections(store)).find((n) => n.id === type) ?? null,
    null,
    [type],
  );
  const entities = useLiveValue<CollectionEntityResponse[]>(
    DERIVED_TABLES,
    (store) => readCollectionEntities(store, type),
    EMPTY_ENTITIES,
    [type],
  );

  const collMeta = collectionTypeMeta(type);
  const typeMeta = saveTypeMeta(type);
  // Pinned first within each section — a curation signal, not a re-sort of
  // the whole list, so "remaining" and "done" stay the sections that matter.
  const bySectionOrder = (a: CollectionEntityResponse, b: CollectionEntityResponse) =>
    Number(b.state?.pinned === true) - Number(a.state?.pinned === true);
  const remaining = entities.filter((e) => e.state?.done !== true).sort(bySectionOrder);
  const done = entities.filter((e) => e.state?.done === true).sort(bySectionOrder);
  const selected = entities.find((e) => e.entityKey === selectedKey) ?? null;

  /**
   * Optimistic through the store rather than through local component state:
   * the entity list, the section counts and the same entity's controls on a
   * save's own detail screen all read `entity_states`, so one write updates
   * every one of them without any of them knowing about the others.
   */
  const setEntityState = useCallback((entity: CollectionEntityResponse, nextState: Record<string, unknown>) => {
    writeEntityState(entity.entityKey, nextState);
  }, []);
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
   * path as done/rating.
   */
  const togglePin = useCallback(
    (entity: CollectionEntityResponse) =>
      setEntityState(entity, { ...entity.state, pinned: entity.state?.pinned !== true }),
    [setEntityState],
  );

  // No error branch any more: reading a derived view of local data cannot
  // fail the way a request could. An empty screen here means the saves behind
  // this collection have not synced yet, which is a spinner, not a failure.
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

          <AppText variant="title" style={{ marginBottom: spacing.xs }}>
            {title}
          </AppText>
          <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xxl - 2 }}>
            {entities.length} {collMeta.entityNoun(entities.length)}
            {done.length > 0 ? ` · ${done.length} ${collMeta.doneNoun}` : ''}
            {node ? ` · ${node.sourceCount} ${node.sourceCount === 1 ? 'source' : 'sources'}` : ''}
          </AppText>
        </Reveal>

        {entities.length === 0 ? (
          <Reveal index={1}>
            <Card>
              <AppText variant="caption" tone="muted">
                Nothing merged into this collection yet.
              </AppText>
            </Card>
          </Reveal>
        ) : (
          <>
            {remaining.length > 0 ? (
              <Reveal index={1}>
                <SectionLabel>Remaining</SectionLabel>
                <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
                  {remaining.map((entity) => (
                    <EntityRow
                      key={entity.entityKey}
                      entity={entity}
                      type={type}
                      onPress={() => setSelectedKey(entity.entityKey)}
                    />
                  ))}
                </View>
              </Reveal>
            ) : null}

            {done.length > 0 ? (
              <Reveal index={2}>
                <SectionLabel>{collMeta.doneNoun[0].toUpperCase() + collMeta.doneNoun.slice(1)}</SectionLabel>
                <View style={{ gap: spacing.sm }}>
                  {done.map((entity) => (
                    <EntityRow
                      key={entity.entityKey}
                      entity={entity}
                      type={type}
                      onPress={() => setSelectedKey(entity.entityKey)}
                    />
                  ))}
                </View>
              </Reveal>
            ) : null}
          </>
        )}
      </Screen>

      {selected ? (
        <EntityDetailSheet
          entity={selected}
          type={type}
          onClose={() => setSelectedKey(null)}
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
