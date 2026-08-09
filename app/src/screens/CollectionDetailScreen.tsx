import { useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ActivityIndicator, Image, Modal, ScrollView, View } from 'react-native';

import type { CollectionEntityResponse, CollectionNodeResponse, SaveResponse } from '@/api/types';
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
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import {
  collectionTypeMeta,
  nodeDepth,
  nodeEntityNoun,
  nodeType,
} from '@/collections/collectionMeta';
import { displayName } from '@/knowledge/facets';
import { saveTitle } from '@/saves/format';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';

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
  onPress,
}: {
  node: CollectionNodeResponse;
  type: string;
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
 * One entity, as a row.
 *
 * The source count is deliberately worded as "in N saves" rather than
 * "N sources": the number is only interesting when it is greater than one,
 * where it says *this keeps coming up*. A bare "1 source" on every row is
 * noise that makes the extraction structure visible for no benefit, so it is
 * omitted entirely.
 */
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
  const done = entity.state?.done === true;
  const pinned = entity.state?.pinned === true;
  const crossSource = entity.sourceCount > 1;

  const kind = collectionTypeMeta(type).showsKind ? clean(entity.kind) : null;
  const meta = [kind, clean(entity.fields.year)].filter((v): v is string => !!v).join(' · ');

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={
          crossSource ? `${entity.name}, in ${entity.sourceCount} saves` : entity.name
        }
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
          {meta || crossSource ? (
            <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
              {[meta, crossSource ? `in ${entity.sourceCount} saves` : null].filter(Boolean).join(' · ')}
            </AppText>
          ) : null}
        </View>
        {pinned ? <Glyph name="bookmark" size={14} weight={2} color={palette.accent} /> : null}
        {done ? <Glyph name="checkSquare" size={16} color={palette.accent} /> : null}
      </Touchable>
    </Card>
  );
}

/**
 * The entity detail sheet — every source's own account of this entity,
 * attributed to the save it came from, with a way back to each.
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
  onToggleDone,
  onRate,
  onTogglePin,
}: {
  entity: CollectionEntityResponse;
  type: string;
  sourceTitles: Map<string, string>;
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
              {[collMeta.showsKind ? clean(entity.kind) : null, clean(entity.fields.year)]
                .filter(Boolean)
                .join(' · ') || 'No details yet'}
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

            {/*
              "Mentioned in 3 saves", with the saves named. This is the whole
              point of the provenance block: the user should never have to
              think "this Tokyo came from that reel", but *how many* of their
              saves agree on something is exactly the signal a pile of
              individual saves cannot give them.
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
 * `nodeId` is the tree's own id (`itinerary~japan`), so a deep link is just a
 * node id and needs no separate route per level.
 */
export function CollectionDetailScreen({ nodeId }: { nodeId: string }) {
  const { palette, spacing } = useTheme();
  const router = useRouter();

  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  const type = nodeType(nodeId);

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
  // Only for naming a source in the entity sheet — a save's title is not on
  // `CollectionSource`, and looking it up locally costs nothing.
  const sources = useLiveValue<SaveResponse[]>(
    DERIVED_TABLES,
    (store) => readCollectionSources(store, nodeId),
    EMPTY_SAVES,
    [nodeId],
  );

  const collMeta = collectionTypeMeta(type);
  const typeMeta = saveTypeMeta(type);
  const depth = nodeDepth(nodeId);

  const sourceTitles = new Map(sources.map((save) => [save.id, saveTitle(save)]));

  // Entities shown *here* are the ones this node holds directly: anything
  // that lives in a folder is reached through the folder instead, so nothing
  // is listed twice on one screen.
  const subgroups = node?.subgroups ?? [];
  const ownKeys = new Set(node?.entityKeys ?? []);
  const shown = subgroups.length > 0 ? entities.filter((e) => ownKeys.has(e.entityKey)) : entities;

  // Pinned first within each section — a curation signal, not a re-sort of
  // the whole list.
  const bySectionOrder = (a: CollectionEntityResponse, b: CollectionEntityResponse) =>
    Number(b.state?.pinned === true) - Number(a.state?.pinned === true);
  const open = shown.filter((e) => e.state?.done !== true).sort(bySectionOrder);
  const done = shown.filter((e) => e.state?.done === true).sort(bySectionOrder);
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
    subgroups.length > 0
      ? `${subgroups.length} ${collMeta.groupNoun(subgroups.length, depth)}`
      : null,
    `${entityCount} ${nodeEntityNoun(type, nodeId, entityCount)}`,
    node ? sourceLabel(node.sourceCount) : null,
    done.length > 0 ? `${done.length} ${collMeta.doneNoun}` : null,
  ].filter(Boolean);

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

          {/*
            A folder shows which collection it belongs to, so "Japan" is never
            adrift — the header is the breadcrumb the stack cannot draw.
          */}
          {depth > 0 ? (
            <AppText variant="caption" tone="muted" style={{ marginBottom: 2 }}>
              {displayName(type)}
            </AppText>
          ) : null}
          <AppText variant="title" style={{ marginBottom: spacing.xs }}>
            {title}
          </AppText>
          <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xxl - 2 }}>
            {summary.join(' · ')}
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
            {subgroups.length > 0 ? (
              <Reveal index={1}>
                <SectionLabel>
                  {`${collMeta.groupNoun(subgroups.length, depth)[0].toUpperCase()}${collMeta
                    .groupNoun(subgroups.length, depth)
                    .slice(1)}`}
                </SectionLabel>
                <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
                  {subgroups.map((child) => (
                    <SubgroupRow
                      key={child.id}
                      node={child}
                      type={type}
                      onPress={() =>
                        router.push({ pathname: '/collection/[type]', params: { type: child.id } })
                      }
                    />
                  ))}
                </View>
              </Reveal>
            ) : null}

            {/*
              The section is named for what the objects *are* — Places,
              Watchlist, Exercises — not "Remaining". Only a checklist is a
              list of things left to do; an itinerary's places are not tasks,
              and heading them that way was what made every collection read
              like the same generic to-do screen.
            */}
            {open.length > 0 ? (
              <Reveal index={2}>
                <SectionLabel>{collMeta.sectionLabel}</SectionLabel>
                <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
                  {open.map((entity) => (
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
              <Reveal index={3}>
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
          sourceTitles={sourceTitles}
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
