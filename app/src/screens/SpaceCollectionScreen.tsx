import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';

import type {
  CollectionEntityResponse,
  CollectionNodeResponse,
  SpaceEntityResponse,
  SpaceMemberState,
} from '@/api/types';
import { repo } from '@/data';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readSpaceCollectionEntities, readSpaceCollectionNode } from '@/local/derived';
import { writeEntityState } from '@/local/writes';
import { useSession } from '@/auth/SessionProvider';
import { AppText } from '@/components/AppText';
import { Avatar } from '@/components/Avatar';
import { Card } from '@/components/Card';
import { EntityDiscussion } from '@/components/EntityDiscussion';
import { StarRating, StatusPill } from '@/components/EntityControls';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { collectionTypeMeta, nodeEntityNoun, nodeType } from '@/collections/collectionMeta';
import { entityMetaLine } from '@/collections/entityFields';
import { bySection, currentStatus, nextStatus, stateForStatus, statusesFor } from '@/collections/entityStatus';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * The Space's own view of one collection node — S1 and S2 of
 * [docs/knowledge-spaces.md](../../../docs/knowledge-spaces.md).
 *
 * This is the screen the S0 Overview's collection row deliberately did **not**
 * link to, and the reason it now can: `/collection/[nodeId]` renders the
 * viewer's *whole library*, so routing a Space's row there showed a different
 * set of entities under the Space's heading. This one is scoped to the Space's
 * saves, and adds the two things a personal collection cannot say — who put
 * each title here, and where everyone else has got to with it.
 *
 * **Read twice, deliberately.** The entity list is derived from the local
 * store first (instant, offline, no request), then replaced by the server's
 * answer when it lands. Only the server can supply `addedBy` and other
 * members' states, and only the local derivation can paint before the network
 * does; running both is what makes this screen behave like the rest of the app
 * rather than like a loading spinner with a Space in it.
 */
const EMPTY_ENTITIES: CollectionEntityResponse[] = [];

function capitalise(value: string): string {
  return value ? value[0].toUpperCase() + value.slice(1) : value;
}

function clean(value: unknown): string | null {
  if (typeof value === 'number') return String(value);
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed && trimmed !== '[unclear]' ? trimmed : null;
}

/**
 * The merged, Space-scoped shape this screen renders — the server's when it has
 * it, the local one until then.
 *
 * Both extras are optional because the locally derived list has neither: member
 * states and comment counts are the two things a client-side merge cannot
 * produce, which is exactly what the server read is for.
 */
type Entity = CollectionEntityResponse & {
  memberStates?: SpaceMemberState[];
  commentCount?: number;
};

/**
 * One member's mark on an entity, as a face plus a word.
 *
 * Names rather than a bare count: "Watched by Sam" is a fact about a person
 * the reader knows, where "1 watched" on a shared list is a statistic about
 * nobody. This is the whole difference between a Space's watchlist and the
 * personal one beside it.
 */
function MemberMarks({
  members,
  viewerId,
  type,
}: {
  members: SpaceMemberState[];
  viewerId: string | null;
  type: string;
}) {
  const { palette, spacing } = useTheme();
  const meta = collectionTypeMeta(type);

  const others = members.filter((member) => member.userId !== viewerId);
  if (others.length === 0) return null;

  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm }}>
      {others.map((member) => {
        const status = currentStatus(type, member.state);
        const label = status ? status.label : member.state?.done === true ? capitalise(meta.doneNoun) : null;
        if (!label) return null;
        return (
          <View
            key={member.userId}
            style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
          >
            <Avatar id={member.userId} name={member.displayName} size={18} />
            <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
              {label} · {member.displayName ?? 'a member'}
            </AppText>
          </View>
        );
      })}
      {/* A rating somebody else gave is worth surfacing where it exists, but as
          theirs — never folded into an average, which would be exactly the
          blending K1 refused for `reason`. */}
      {others
        .filter((member) => typeof member.state?.rating === 'number')
        .map((member) => (
          <View
            key={`${member.userId}-rating`}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}
          >
            <Glyph name="star" size={11} weight={2} color={palette.accent} />
            <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
              {String(member.state.rating)}
            </AppText>
          </View>
        ))}
    </View>
  );
}

/**
 * Why each member put this here, attributed and unblended.
 *
 * The rolled-up `reason` is deliberately not shown as a headline anywhere
 * (see `entityFields`) because it is one source's, picked arbitrarily by the
 * scalar rollup. In a Space it is the most interesting thing on the row —
 * "Sam: best enemies-to-lovers arc" and "Maya: underrated gem" are two people
 * recommending the same title for different reasons, which is the argument for
 * the whole redesign.
 */
function SourceReasons({
  entity,
  nameFor,
  onOpenSave,
}: {
  entity: Entity;
  nameFor: (userId: string | undefined) => string | null;
  onOpenSave: (saveId: string) => void;
}) {
  const { spacing } = useTheme();
  const reasons = entity.sources
    .map((source) => ({
      saveId: source.saveId,
      who: nameFor(source.addedBy),
      why: clean(source.item.reason) ?? clean(source.item.detail),
    }))
    .filter((row) => row.why || row.who);

  if (reasons.length === 0) return null;

  return (
    <View style={{ gap: spacing.xs }}>
      {reasons.map((row) => (
        <Touchable
          key={row.saveId}
          accessibilityRole="button"
          accessibilityLabel={`Open the source${row.who ? ` from ${row.who}` : ''}`}
          onPress={() => onOpenSave(row.saveId)}
          haptic="selection"
        >
          <AppText variant="caption" tone="muted" style={{ fontSize: 11.5 }}>
            {row.who ? (
              <AppText variant="caption" tone="accent" style={{ fontSize: 11.5 }}>
                {row.who}
                {row.why ? ': ' : ' added this'}
              </AppText>
            ) : null}
            {row.why ?? ''}
          </AppText>
        </Touchable>
      ))}
    </View>
  );
}

function SpaceEntityRow({
  entity,
  type,
  spaceId,
  viewerId,
  nameFor,
  onCycleStatus,
  onToggleDone,
  onRate,
  onOpenSave,
}: {
  entity: Entity;
  type: string;
  spaceId: string;
  viewerId: string | null;
  nameFor: (userId: string | undefined) => string | null;
  onCycleStatus: () => void;
  onToggleDone: () => void;
  onRate: (rating: number) => void;
  onOpenSave: (saveId: string) => void;
}) {
  const { palette, radius, spacing } = useTheme();
  /**
   * S3's thread, collapsed by default and fetched only once opened.
   *
   * A list of twenty titles each eagerly loading its own discussion is twenty
   * requests for something nobody has looked at. The server's batched
   * `commentCount` is what makes the collapsed state honest without them — it
   * rides in with the entities, one query for the whole screen.
   *
   * `count` starts from that number and moves optimistically, so sending a
   * remark updates the row it came from rather than waiting for a refetch that
   * offline never arrives.
   */
  const [open, setOpen] = useState(false);
  const [count, setCount] = useState<number | null>(null);
  const commentCount = count ?? entity.commentCount ?? 0;
  const typeMeta = saveTypeMeta(type);
  const collMeta = collectionTypeMeta(type);
  const status = currentStatus(type, entity.state);
  const done = entity.state?.done === true;
  const rating = typeof entity.state?.rating === 'number' ? entity.state.rating : 0;
  const kind = collMeta.showsKind ? clean(entity.kind) : null;
  const meta = entityMetaLine(type, kind, entity.fields);
  const crossSource = entity.sourceCount > 1;

  return (
    <Card padding={0} radius={radius.md}>
      <View style={{ padding: spacing.md, gap: spacing.sm }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
          <View
            style={{
              width: 34,
              height: 34,
              borderRadius: radius.sm,
              backgroundColor: `${typeMeta.color}26`,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name={typeMeta.glyph} size={15} weight={2} color={typeMeta.color} />
          </View>
          <View style={{ flex: 1 }}>
            <AppText variant="cardTitle" numberOfLines={1}>
              {entity.name}
            </AppText>
            {meta || crossSource ? (
              <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
                {[meta, crossSource ? `recommended by ${entity.sourceCount}` : null]
                  .filter(Boolean)
                  .join(' · ')}
              </AppText>
            ) : null}
          </View>
        </View>

        <SourceReasons entity={entity} nameFor={nameFor} onOpenSave={onOpenSave} />

        {entity.memberStates && entity.memberStates.length > 0 ? (
          <MemberMarks members={entity.memberStates} viewerId={viewerId} type={type} />
        ) : null}

        {/* The viewer's own control, always last: everything above is the
            group's view, this is the one row they can change. */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md, flexWrap: 'wrap' }}>
          {status ? (
            <StatusPill status={status} name={entity.name} onPress={onCycleStatus} />
          ) : (
            <Touchable
              accessibilityRole="checkbox"
              accessibilityState={{ checked: done }}
              accessibilityLabel={`${entity.name}: ${done ? `mark as not ${collMeta.doneNoun}` : `mark ${collMeta.doneNoun}`}`}
              onPress={onToggleDone}
              haptic="light"
              style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
            >
              <Glyph
                name={done ? 'checkSquare' : 'square'}
                size={16}
                color={done ? palette.accent : palette.textMuted}
              />
              <AppText variant="caption" tone={done ? 'accent' : 'muted'}>
                {done ? capitalise(collMeta.doneNoun) : `Mark ${collMeta.doneNoun}`}
              </AppText>
            </Touchable>
          )}
          {collMeta.ratable ? <StarRating rating={rating} name={entity.name} onRate={onRate} /> : null}

          {/* S3. Beside the status control rather than behind a tap into a
              sheet: on a shared list, "what did everyone think" is the second
              thing anybody wants after "have they seen it", and a screen that
              hides discussion one navigation away is a read-only list again. */}
          <Touchable
            accessibilityRole="button"
            accessibilityState={{ expanded: open }}
            accessibilityLabel={
              open
                ? `Hide discussion of ${entity.name}`
                : commentCount > 0
                  ? `${commentCount} ${commentCount === 1 ? 'comment' : 'comments'} on ${entity.name}`
                  : `Discuss ${entity.name}`
            }
            onPress={() => setOpen((current) => !current)}
            haptic="selection"
            style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
          >
            <Glyph
              name="messageCircle"
              size={14}
              weight={2}
              color={commentCount > 0 ? palette.accent : palette.textMuted}
            />
            <AppText variant="caption" tone={commentCount > 0 ? 'accent' : 'muted'}>
              {commentCount > 0
                ? `${commentCount} ${commentCount === 1 ? 'comment' : 'comments'}`
                : 'Discuss'}
            </AppText>
          </Touchable>
        </View>

        {open ? (
          <EntityDiscussion
            spaceId={spaceId}
            entityKey={entity.entityKey}
            entityName={entity.name}
            onCountChange={(delta) => setCount((current) => (current ?? commentCount) + delta)}
          />
        ) : null}
      </View>
    </Card>
  );
}

export function SpaceCollectionScreen({ spaceId, nodeId }: { spaceId: string; nodeId: string }) {
  const { palette, spacing } = useTheme();
  const router = useRouter();
  const { session } = useSession();
  const viewerId = session?.user.id ?? null;

  const [remote, setRemote] = useState<SpaceEntityResponse[] | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const type = nodeType(nodeId);
  const collMeta = collectionTypeMeta(type);

  const node = useLiveValue<CollectionNodeResponse | null>(
    DERIVED_TABLES,
    (store) => readSpaceCollectionNode(store, spaceId, nodeId),
    null,
    [spaceId, nodeId],
  );
  const local = useLiveValue<CollectionEntityResponse[]>(
    DERIVED_TABLES,
    (store) => readSpaceCollectionEntities(store, spaceId, nodeId),
    EMPTY_ENTITIES,
    [spaceId, nodeId],
  );

  /**
   * The server's list wins when it exists, but only for the *shared* half.
   *
   * The viewer's own `state` is always taken from the local store, because
   * that is where their taps land — adopting the server's copy would make a
   * status flip back to its old value for as long as the fetch that was
   * already in flight takes to arrive. This is the same reason every other
   * optimistic write here reads through the store rather than through the
   * response it just triggered.
   */
  const localStates = useMemo(
    () => new Map(local.map((entity) => [entity.entityKey, entity.state])),
    [local],
  );
  const entities: Entity[] = useMemo(() => {
    if (!remote) return local;
    return remote.map((entity) => ({ ...entity, state: localStates.get(entity.entityKey) ?? entity.state }));
  }, [remote, local, localStates]);

  const load = useCallback(async () => {
    try {
      setRemote(await repo.listSpaceCollectionEntities(spaceId, nodeId));
    } catch {
      // The locally derived list is already on screen and is not wrong — it is
      // missing attribution, not showing the wrong entities. Replacing it with
      // an error card would take away everything to show what one section
      // could not load.
    }
  }, [spaceId, nodeId]);

  useEffect(() => {
    void load();
  }, [load]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

  /** Display names for `addedBy`, from the states the same response carried. */
  const namesById = useMemo(() => {
    const names = new Map<string, string>();
    (remote ?? []).forEach((entity) =>
      entity.memberStates.forEach((member) => {
        if (member.displayName) names.set(member.userId, member.displayName);
      }),
    );
    return names;
  }, [remote]);
  const nameFor = useCallback(
    (userId: string | undefined) => {
      if (!userId) return null;
      if (userId === viewerId) return 'You';
      return namesById.get(userId) ?? null;
    },
    [namesById, viewerId],
  );

  /**
   * Writes go to `entity_states` exactly as they do from the personal
   * collection — global, not Space-scoped. That is the storage decision K2
   * made and S2 kept: completing Your Name is a fact about the person, not
   * about a room they happen to be in, and forking it per Space would
   * fragment the single fact the entity layer exists to keep whole. The
   * People tab says so in as many words.
   */
  const setEntityState = useCallback((entity: Entity, next: Record<string, unknown>) => {
    writeEntityState(entity.entityKey, next);
  }, []);

  /**
   * Entities shown *here* are the ones this node holds directly: anything in a
   * folder is reached through the folder instead, so nothing is listed twice on
   * one screen. Same rule as `CollectionDetailScreen`, and it is not cosmetic —
   * without it a node with one folder shows every title above the folder that
   * claims to contain them.
   */
  const hasFolders = (node?.subgroups.length ?? 0) > 0;
  const ownKeys = useMemo(() => new Set(node?.entityKeys ?? []), [node]);
  const shown = useMemo(
    () => (hasFolders ? entities.filter((entity) => ownKeys.has(entity.entityKey)) : entities),
    [hasFolders, entities, ownKeys],
  );

  const sections = useMemo(() => {
    if (statusesFor(type)) {
      return bySection(type, shown).map((s) => ({ label: s.status.label, entities: s.entities }));
    }
    const open = shown.filter((e) => e.state?.done !== true);
    const done = shown.filter((e) => e.state?.done === true);
    return [
      { label: collMeta.sectionLabel, entities: open },
      { label: capitalise(collMeta.doneNoun), entities: done },
    ].filter((s) => s.entities.length > 0);
  }, [shown, type, collMeta.sectionLabel, collMeta.doneNoun]);

  /**
   * "3 watched" here counts anybody, not the viewer — the same answer the
   * server's Space-scoped `doneCount` gives, computed from the same member
   * states so the two can never disagree. On a shared list the viewer's own
   * count under a group heading would read as a claim about the group, which
   * is precisely why S0 showed no count at all.
   */
  const groupDone = useMemo(
    () =>
      entities.filter(
        (entity) =>
          entity.state?.done === true ||
          (entity.memberStates ?? []).some((member) => member.state?.done === true),
      ).length,
    [entities],
  );

  if (!node && entities.length === 0) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  const title = node?.name ?? saveTypeMeta(type).label;
  const entityCount = node?.entityCount ?? entities.length;
  const summary = [
    `${entityCount} ${nodeEntityNoun(type, nodeId, entityCount)}`,
    `${node?.sourceCount ?? 0} ${node?.sourceCount === 1 ? 'source' : 'sources'}`,
    groupDone > 0 ? `${groupDone} ${collMeta.doneNoun}` : null,
  ].filter(Boolean);

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
      <Reveal index={0}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Back to the Space"
          onPress={() => router.back()}
          haptic="selection"
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            alignSelf: 'flex-start',
            gap: spacing.xs + 2,
            marginBottom: spacing.md,
          }}
        >
          <Glyph name="layers" size={14} weight={2} />
          <AppText tone="muted" style={{ fontSize: 12.5 }}>
            Space
          </AppText>
        </Touchable>
      </Reveal>

      <Reveal index={1} style={{ marginBottom: spacing.xl }}>
        <AppText variant="title">{title}</AppText>
        <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
          {summary.join(' · ')}
        </AppText>
      </Reveal>

      {node && node.subgroups.length > 0 ? (
        <Reveal index={2} style={{ marginBottom: spacing.xl }}>
          <SectionLabel>Folders</SectionLabel>
          <View style={{ gap: spacing.sm }}>
            {node.subgroups.map((child) => (
              <Card key={child.id} padding={0}>
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel={`${child.name}, ${child.entityCount} ${nodeEntityNoun(type, child.id, child.entityCount)}`}
                  onPress={() =>
                    router.push({
                      pathname: '/space/[id]/collection/[nodeId]',
                      params: { id: spaceId, nodeId: child.id },
                    })
                  }
                  haptic="selection"
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.smd,
                    padding: spacing.md,
                  }}
                >
                  <Glyph name="folder" size={16} weight={2} color={palette.textMuted} />
                  <View style={{ flex: 1 }}>
                    <AppText variant="cardTitle" numberOfLines={1}>
                      {child.name}
                    </AppText>
                    <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
                      {child.entityCount} {nodeEntityNoun(type, child.id, child.entityCount)}
                    </AppText>
                  </View>
                  <View style={{ transform: [{ scaleX: -1 }] }}>
                    <Glyph name="chevron" size={14} color={palette.textFaint} />
                  </View>
                </Touchable>
              </Card>
            ))}
          </View>
        </Reveal>
      ) : null}

      {sections.length === 0 && !hasFolders ? (
        <Card>
          <AppText variant="caption" tone="muted">
            Nothing here yet. What anyone saves into this Space shows up in this list.
          </AppText>
        </Card>
      ) : (
        sections.map((section, i) => (
          <Reveal key={section.label} index={i + 3}>
            <SectionLabel>{section.label}</SectionLabel>
            <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
              {section.entities.map((entity) => (
                <SpaceEntityRow
                  key={entity.entityKey}
                  entity={entity}
                  type={type}
                  spaceId={spaceId}
                  viewerId={viewerId}
                  nameFor={nameFor}
                  onCycleStatus={() => {
                    const next = nextStatus(type, entity.state);
                    if (next) setEntityState(entity, stateForStatus(entity.state, next));
                  }}
                  onToggleDone={() =>
                    setEntityState(entity, { ...entity.state, done: entity.state?.done !== true })
                  }
                  onRate={(rating) => setEntityState(entity, { ...entity.state, rating })}
                  onOpenSave={(saveId) =>
                    router.push({ pathname: '/save/[id]', params: { id: saveId } })
                  }
                />
              ))}
            </View>
          </Reveal>
        ))
      )}

      {/* The disclosure, stated where the shared statuses are actually visible
          rather than only in the People tab — see `SpaceKnowledgeService`. */}
      <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.lg }}>
        Progress is shared with everyone in this Space.
      </AppText>
    </Screen>
  );
}
