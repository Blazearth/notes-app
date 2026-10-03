import * as Clipboard from 'expo-clipboard';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  RefreshControl,
  TextInput,
  View,
} from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import { getStore, useLive, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import { writePin, writeUnpin } from '@/local/writes';
import type {
  ActivityEntry,
  DuplicateSuggestion,
  SaveResponse,
  ShoppingListResponse,
  Space,
  SpaceCommentEntry,
  SpaceKnowledgeOverview,
  SpaceMember,
  SpaceMemberProgress,
  SpacePin,
} from '@/api/types';
import { AppText } from '@/components/AppText';
import { Avatar } from '@/components/Avatar';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Segmented } from '@/components/Segmented';
import { Touchable } from '@/components/Touchable';
import { collectionTypeMeta } from '@/collections/collectionMeta';
import { relativeTime, saveTitle } from '@/saves/format';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { buildSpaceOverview, spaceDefaultTab, type SpaceOverview } from '@/spaces/spaceOverview';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * S0 of `docs/knowledge-spaces.md`: Overview leads, and **Saves is renamed
 * Sources everywhere it shows**. The rename is the mental-model statement, not
 * a label tweak — a Space's saves are the evidence behind its knowledge, not
 * the product. App copy only: `/v1/saves` and every payload field keep their
 * names, since a breaking rename buys nothing.
 */
type TabValue = 'overview' | 'sources' | 'people' | 'activity';

/** Stable identities for the "store has nothing yet" case — see `useLiveValue`. */
const EMPTY_SAVES: SaveResponse[] = [];
const EMPTY_MEMBERS: SpaceMember[] = [];

/** Past-tense phrasing per activity type, so the feed reads as sentences. */
const ACTIVITY_VERBS: Record<string, string> = {
  space_created: 'created this Space',
  member_joined: 'joined',
  save_added: 'added',
  completed: 'completed',
  commented: 'commented on',
  voted: 'voted on',
  duplicate_suggested: 'may have added a duplicate of',
};

function ActivityRow({ entry }: { entry: ActivityEntry }) {
  const { spacing } = useTheme();
  const verb = ACTIVITY_VERBS[entry.type] ?? entry.type.replace(/_/g, ' ');
  return (
    <View style={{ paddingVertical: spacing.sm }}>
      <AppText variant="bodySmall">
        <AppText variant="bodySmall" tone="accent">
          {entry.displayName}
        </AppText>
        {` ${verb}`}
        {entry.saveTitle ? ` ${entry.saveTitle}` : ''}
      </AppText>
      <AppText variant="caption" tone="muted" style={{ marginTop: 1 }}>
        {relativeTime(entry.createdAt)}
      </AppText>
    </View>
  );
}

/**
 * A merge suggestion. Deliberately prominent but never automatic: the server
 * found two saves whose embeddings are close, which is evidence rather than
 * proof, and merging someone else's save is not something this screen can undo.
 */
function DuplicateCard({
  suggestion,
  canAct,
  onDismiss,
  onMerge,
}: {
  suggestion: DuplicateSuggestion;
  canAct: boolean;
  onDismiss: () => void;
  onMerge: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  return (
    <Card radius={radius.md} style={{ marginBottom: spacing.sm }}>
      <AppText variant="label" tone="accent" style={{ marginBottom: spacing.xs }}>
        Possible duplicate
      </AppText>
      <AppText variant="bodySmall" style={{ marginBottom: spacing.smd }}>
        “{suggestion.saveTitle ?? 'A new save'}” looks like the same thing as “
        {suggestion.duplicateTitle ?? 'one already here'}”.
      </AppText>
      {canAct ? (
        <View style={{ flexDirection: 'row', gap: spacing.sm }}>
          <Touchable
            accessibilityRole="button"
            onPress={onMerge}
            haptic="selection"
            style={{
              paddingVertical: spacing.xs + 2,
              paddingHorizontal: spacing.md,
              borderRadius: radius.sm,
              backgroundColor: palette.accent,
            }}
          >
            <AppText variant="caption" style={{ color: palette.onAccent }}>
              Same thing
            </AppText>
          </Touchable>
          <Touchable
            accessibilityRole="button"
            onPress={onDismiss}
            haptic="selection"
            style={{
              paddingVertical: spacing.xs + 2,
              paddingHorizontal: spacing.md,
              borderRadius: radius.sm,
              borderWidth: 1,
              borderColor: palette.border,
            }}
          >
            <AppText variant="caption" tone="muted">
              Different
            </AppText>
          </Touchable>
        </View>
      ) : (
        <AppText variant="caption" tone="muted">
          An editor can resolve this.
        </AppText>
      )}
    </Card>
  );
}

/** One number and what it counts. Deliberately flat — a Space is not a dashboard. */
function StatTile({ value, label }: { value: number; label: string }) {
  const { radius, spacing } = useTheme();
  return (
    <Card radius={radius.md} padding={spacing.md} style={{ flex: 1, minWidth: 84 }}>
      <AppText variant="title">{value}</AppText>
      <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 1 }}>
        {label}
      </AppText>
    </Card>
  );
}

/**
 * A derived collection, as a summary row.
 *
 * **Tappable as of S1**, and it was deliberately inert before that. The row
 * used to have nowhere honest to go: `/collection/[type]` renders the viewer's
 * *whole library*, so a Space's row would have shown a different set of
 * entities under the Space's heading, and an affordance that goes somewhere
 * wrong is worse than none. `/space/[id]/collection/[nodeId]` is the
 * Space-scoped list it was waiting for.
 *
 * `doneCount` here counts entities *anyone* in the Space has finished — the
 * group fact S0 refused to show because only the viewer's own was available.
 */
function CollectionSummaryRow({
  summary,
  doneCount,
  pinned,
  canEdit,
  onPress,
  onTogglePin,
}: {
  summary: SpaceOverview['collections'][number];
  doneCount: number;
  pinned: boolean;
  canEdit: boolean;
  onPress: () => void;
  onTogglePin: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const typeMeta = saveTypeMeta(summary.type);
  const collMeta = collectionTypeMeta(summary.type);
  const counts = [
    `${summary.entityCount} ${collMeta.entityNoun(summary.entityCount)}`,
    `${summary.sourceCount} ${summary.sourceCount === 1 ? 'source' : 'sources'}`,
    doneCount > 0 ? `${doneCount} ${collMeta.doneNoun}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${summary.name}, ${counts}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        <View
          style={{
            width: 36,
            height: 36,
            borderRadius: radius.sm,
            backgroundColor: `${typeMeta.color}26`,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name={typeMeta.glyph} size={16} weight={2} color={typeMeta.color} />
        </View>
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={1}>
            {summary.name}
          </AppText>
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
            {counts}
          </AppText>
        </View>
        <View style={{ transform: [{ scaleX: -1 }] }}>
          <Glyph name="chevron" size={icon.sm} color={palette.textFaint} />
        </View>
      </Touchable>
      {/* S4: a pin on a collection is a Space that has several saying which one
          leads. A viewer sees the ordering it produces but no control — pinning
          changes what everybody sees, so it takes editor, the same bar as
          adding content. */}
      {canEdit ? (
        <Touchable
          accessibilityRole="button"
          accessibilityState={{ selected: pinned }}
          accessibilityLabel={pinned ? `Unpin ${summary.name}` : `Pin ${summary.name} to the top`}
          onPress={onTogglePin}
          haptic="selection"
          style={{
            position: 'absolute',
            top: 0,
            right: 0,
            padding: spacing.sm,
          }}
        >
          <Glyph name="pin" size={13} weight={2} color={pinned ? palette.accent : palette.textFaint} />
        </Touchable>
      ) : null}
    </Card>
  );
}

/**
 * One member's line in the shared-progress block — S2.
 *
 * Only members who have actually touched something appear. A roster with a
 * zero beside everyone who has not started reads as a scoreboard nobody asked
 * to be on; a list of who is participating reads as the group moving.
 */
function MemberProgressRow({ progress }: { progress: SpaceMemberProgress }) {
  const { spacing } = useTheme();
  const parts = [
    progress.doneCount > 0 ? `${progress.doneCount} done` : null,
    progress.inProgressCount > 0 ? `${progress.inProgressCount} in progress` : null,
  ].filter(Boolean);

  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.smd,
        paddingVertical: spacing.xs,
      }}
    >
      <Avatar id={progress.userId} name={progress.displayName} size={26} />
      <AppText variant="bodySmall" style={{ flex: 1 }} numberOfLines={1}>
        {progress.displayName ?? 'A Weavr user'}
      </AppText>
      <AppText variant="caption" tone="muted">
        {parts.join(' · ')}
      </AppText>
    </View>
  );
}

/**
 * The discussion block — the latest comments across the Space's saves.
 *
 * S0 left this out rather than approximating it from the activity feed's
 * `commented` rows, because surfacing real discussion would have cost one
 * request per save; S2's single indexed query is what makes it affordable.
 * Save-scoped, not entity-scoped: a remark attached to Blue Box rather than to
 * whichever Reel mentioned it is S3, and only if usage pulls for it.
 */
/**
 * @param onPress absent for an entity comment, and deliberately: the row would
 *                have nowhere honest to go. An entity key does not name a
 *                collection node, so routing to one would be a guess, and
 *                "an affordance that goes somewhere wrong is worse than none"
 *                is the rule this screen already followed when the collection
 *                row was inert through S0.
 */
function CommentRow({ comment, onPress }: { comment: SpaceCommentEntry; onPress?: () => void }) {
  const { spacing } = useTheme();
  // S3 put a second kind of remark in this block: one about a merged entity
  // rather than about a save. They share the block because "what's being
  // talked about in here" is one question — splitting it would ask the reader
  // to care about which table a remark landed in.
  const subject = comment.entityName ?? comment.saveTitle;
  const body = (
    <>
      <Avatar id={comment.userId} name={comment.displayName} size={26} />
      <View style={{ flex: 1 }}>
        <AppText variant="bodySmall" numberOfLines={2}>
          {comment.body}
        </AppText>
        <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
          {comment.displayName}
          {subject ? ` on ${subject}` : ''} · {relativeTime(comment.createdAt)}
        </AppText>
      </View>
    </>
  );
  const style = { paddingVertical: spacing.sm, flexDirection: 'row' as const, gap: spacing.smd };

  if (!onPress) {
    return <View style={style}>{body}</View>;
  }
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={`${comment.displayName} on ${subject ?? 'a source'}: ${comment.body}`}
      onPress={onPress}
      haptic="selection"
      style={style}
    >
      {body}
    </Touchable>
  );
}

/**
 * S4 — what somebody chose to put at the top of this Space.
 *
 * The honest version of the vision's "Current Program". A merged program
 * assembled by the model from several push-day saves is synthesis
 * (`docs/knowledge-collections.md`'s shape 3, ruled out), and its failure mode
 * is a routine in a gym that no human wrote. This says who chose it, because
 * that is the whole difference.
 */
function PinCard({
  pin,
  canEdit,
  onOpen,
  onUnpin,
}: {
  pin: SpacePin;
  canEdit: boolean;
  onOpen: () => void;
  onUnpin: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const scheduled = typeof pin.payload?.date === 'string' ? (pin.payload.date as string) : null;

  return (
    <Card padding={0} radius={radius.md}>
      <View style={{ padding: spacing.md, gap: spacing.sm }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
          <Glyph name="pin" size={15} weight={2} color={palette.accent} />
          <View style={{ flex: 1 }}>
            <Touchable
              accessibilityRole="button"
              accessibilityLabel={`Open ${pin.label ?? 'the pinned item'}`}
              onPress={onOpen}
              haptic="selection"
              disabled={!pin.available}
            >
              <AppText variant="cardTitle" numberOfLines={1}>
                {pin.label ?? pin.subject}
              </AppText>
            </Touchable>
            <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
              {[
                `Pinned by ${pin.createdByName}`,
                scheduled ? `for ${scheduled}` : null,
                // Shown rather than hidden: a pin whose save has left the Space
                // is a thing an editor needs to see in order to clear it, and
                // filtering it out would leave a row nobody can reach.
                pin.available ? null : 'no longer in this Space',
              ]
                .filter(Boolean)
                .join(' · ')}
            </AppText>
          </View>
          {canEdit ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel={`Unpin ${pin.label ?? pin.subject}`}
              onPress={onUnpin}
              haptic="medium"
            >
              <AppText variant="caption" tone="muted">
                Unpin
              </AppText>
            </Touchable>
          ) : null}
        </View>
      </View>
    </Card>
  );
}

/** What the Space actually holds, by knowledge type — the honest line for content that does not merge. */
function TypeChip({ type, count }: { type: string; count: number }) {
  const { palette, radius, spacing } = useTheme();
  const meta = saveTypeMeta(type);
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.xs,
        paddingVertical: spacing.xs,
        paddingHorizontal: spacing.smd,
        borderRadius: radius.pill,
        backgroundColor: palette.surfaceVariant,
      }}
    >
      <Glyph name={meta.glyph} size={12} weight={2} color={meta.color} />
      <AppText variant="caption" tone="muted" style={{ fontSize: 11.5 }}>
        {meta.label} · {count}
      </AppText>
    </View>
  );
}

export function SpaceDetailScreen({ spaceId }: { spaceId: string }) {
  const { palette, radius, spacing } = useTheme();
  const router = useRouter();

  // `null` means "nobody has chosen", which is what lets the default below be a
  // computed opinion rather than a value written into state once at mount and
  // then wrong. A tap pins it for the rest of the visit.
  const [picked, setPicked] = useState<TabValue | null>(null);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [duplicates, setDuplicates] = useState<DuplicateSuggestion[]>([]);
  /**
   * S2's shared half. `null` until it lands, and it may never land — the
   * Overview's stats and collection rows are derived from saves already in
   * the store, so a failure here removes two sections rather than the tab.
   */
  const [knowledge, setKnowledge] = useState<SpaceKnowledgeOverview | null>(null);
  /**
   * S4's pins, held separately from `knowledge` so a pin or unpin shows
   * immediately rather than waiting for a refetch that offline never arrives.
   * Seeded from the same response — `getSpaceKnowledge` carries them so the
   * Overview stays one request.
   */
  const [pins, setPins] = useState<SpacePin[]>([]);
  /**
   * S4's shared list, as a count only. The list itself lives on its own route;
   * what the Overview owes is the fact that there *is* one and how much is
   * outstanding, which is the thing that makes anyone open it.
   */
  const [sharedList, setSharedList] = useState<ShoppingListResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [invite, setInvite] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // ---- Delete Space: owner-only menu + confirmation ----
  const [spaceMenuOpen, setSpaceMenuOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteConfirmText, setDeleteConfirmText] = useState('');
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // The Space, its saves and its members read the local store, so arriving here
  // from the Spaces list paints the header and the saves tab on the first frame.
  const space = useLiveValue<Space | null>(['spaces'], (store) => store.readSpace(spaceId), null, [spaceId]);
  // `useLive` rather than `useLiveValue` here alone: the default tab is a
  // function of these saves, so it has to wait for the first read to resolve.
  // Deciding against an empty list and re-deciding a frame later would move the
  // tab strip under the user's thumb.
  const { data: savesData, loading: savesLoading } = useLive<SaveResponse[]>(
    ['saves'],
    (store) => store.readFeed({ spaceId }),
    [spaceId],
  );
  const saves = savesData ?? EMPTY_SAVES;
  const members = useLiveValue<SpaceMember[]>(
    ['space_members'],
    (store) => store.readSpaceMembers(spaceId),
    EMPTY_MEMBERS,
    [spaceId],
  );

  /**
   * Activity and duplicates stay on-demand network reads, deliberately.
   * Neither table carries an `updated_at`, so neither is delta-syncable
   * (`docs/local-first.md`, "Honestly delta-syncable, and not"), and both are
   * one tap off the default tab — caching them would buy a stale feed rather
   * than a fast one.
   */
  const load = useCallback(async () => {
    // `pullSpace` writes the Space, its saves and its members into the store;
    // the three `useLiveValue`s above pick that up on their own.
    const [detail, a, d, k, s] = await Promise.allSettled([
      sync.pullSpace(spaceId),
      repo.getSpaceActivity(spaceId),
      repo.listDuplicates(spaceId),
      repo.getSpaceKnowledge(spaceId),
      repo.getSpaceShoppingList(spaceId),
    ] as const);
    if (a.status === 'fulfilled') setActivity(a.value);
    if (d.status === 'fulfilled') setDuplicates(d.value);
    if (k.status === 'fulfilled') {
      setKnowledge(k.value);
      setPins(k.value.pins ?? []);
    }
    if (s.status === 'fulfilled') setSharedList(s.value);
    // Only fatal when there is nothing cached to show instead.
    if (detail.status === 'rejected') {
      setError(detail.reason instanceof ApiError ? detail.reason.message : 'Could not load this Space.');
    } else {
      setError(null);
    }
  }, [spaceId]);

  useEffect(() => {
    void load();
  }, [load]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

  const canEdit = space?.myRole === 'owner' || space?.myRole === 'editor';
  // Only the owner can delete a Space — a member never sees the action at
  // all, per the same access-control boundary SpaceService enforces
  // server-side (removing this Space's row is `SpaceRole.OWNER`-gated there
  // too, so a member who somehow triggered this would just get a 403).
  const isOwner = space?.myRole === 'owner';
  // "Substantial shared content" — real collaboration or a real library, not
  // a Space someone just created and hasn't used yet — earns the heavier
  // type-the-name confirmation instead of a plain Cancel/Delete.
  const requiresTypedConfirmation = !!space && (space.saveCount >= 5 || space.memberCount > 1);
  const deleteConfirmMatches =
    !requiresTypedConfirmation || deleteConfirmText.trim() === (space?.name ?? '').trim();

  const openDeleteModal = useCallback(() => {
    setSpaceMenuOpen(false);
    setDeleteConfirmText('');
    setDeleteError(null);
    setDeleteModalOpen(true);
  }, []);

  /**
   * Never optimistic: the Space stays in the local store, and the screen
   * stays put, until the server actually confirms the delete. A rejected
   * request leaves the user exactly where they were, with a reason and a way
   * to try again — the opposite of `writeDeleteSave`'s queue-and-forget
   * shape, which is right for a checkbox tick and wrong for something this
   * destructive and this rare.
   */
  const confirmDeleteSpace = useCallback(async () => {
    if (!space || deleteBusy) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await repo.deleteSpace(space.id);
      await getStore().removeSpace(space.id);
      setDeleteModalOpen(false);
      router.back();
    } catch (e) {
      setDeleteError(
        e instanceof ApiError ? e.message : 'Could not delete this Space. Check your connection and try again.',
      );
    } finally {
      setDeleteBusy(false);
    }
  }, [space, deleteBusy, router]);

  // Derived from saves already in the store — no request, no AI. See
  // `@/spaces/spaceOverview` for what S0 deliberately stops short of.
  const overview = useMemo(
    () => buildSpaceOverview({ saves, memberCount: space?.memberCount ?? 0 }),
    [saves, space?.memberCount],
  );
  const tab: TabValue = picked ?? spaceDefaultTab(overview);

  /**
   * Group done counts, per collection, from the server's own Space-scoped
   * tree — looked up by node id rather than by position, so a collection the
   * local derivation has and the server does not (a save that has synced here
   * but not there, or the reverse) simply shows no count instead of borrowing
   * a neighbour's.
   */
  const doneByCollection = useMemo(() => {
    const counts = new Map<string, number>();
    (knowledge?.collections ?? []).forEach((node) => counts.set(node.id, node.doneCount));
    return counts;
  }, [knowledge]);
  /**
   * S4's two pin kinds, split by what they do to the screen: a save pin leads
   * the tab with a card, a collection pin only reorders the list it is already
   * in (K6's rule that nothing is listed twice on one screen).
   */
  const pinnedSaves = useMemo(() => pins.filter((pin) => pin.kind === 'save'), [pins]);
  const pinnedCollections = useMemo(
    () => new Set(pins.filter((pin) => pin.kind === 'collection').map((pin) => pin.subject)),
    [pins],
  );
  const sortedCollections = useMemo(
    () =>
      [...overview.collections].sort(
        (a, b) => Number(pinnedCollections.has(b.type)) - Number(pinnedCollections.has(a.type)),
      ),
    [overview.collections, pinnedCollections],
  );
  const pinnedSaveIds = useMemo(
    () => new Set(pinnedSaves.map((pin) => pin.subject)),
    [pinnedSaves],
  );
  const outstandingCount = (sharedList?.items ?? []).filter((item) => !item.checked).length;

  /** "Titles" reads better than "Items" when there is exactly one collection to name. */
  const entityLabel =
    overview.collections.length === 1
      ? collectionTypeMeta(overview.collections[0].type).entityNoun(overview.entityCount)
      : overview.entityCount === 1
        ? 'item'
        : 'items';

  const onInvite = useCallback(async () => {
    try {
      // "Anyone with the link", which is what people expect from a link they
      // are about to paste into a group chat. A single-use invite is a
      // different affordance and would need to say so.
      const created = await repo.createInvite(spaceId, { role: 'editor' });
      setInvite(created.code);
      setCopied(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not create an invite.');
    }
  }, [spaceId]);

  /**
   * S4: pinning and unpinning, optimistic and queued.
   *
   * The id for a pin the server has not confirmed is the `local:` one
   * `writePin` returns (the same convention `Discussion` uses), and the next
   * successful load replaces it with the real row. Pin and unpin of one
   * subject share a queue key, so they reach the server in tap order. There is no rollback, per `@/local/writes`:
   * a write the server rejects is surfaced on Settings rather than silently
   * undone.
   */
  const togglePin = useCallback(
    (kind: string, subject: string, label?: string) => {
      const existing = pins.find((p) => p.kind === kind && p.subject === subject);
      if (existing) {
        setPins((current) => current.filter((p) => p.id !== existing.id));
        // Queued even for an unconfirmed pin: its pin request is still on its
        // way, and skipping the unpin left it pinned on the server.
        writeUnpin(spaceId, existing.id, kind, subject);
        return;
      }
      const localId = writePin(spaceId, kind, subject);
      setPins((current) => [
        ...current,
        {
          id: localId,
          kind,
          subject,
          label,
          payload: {},
          createdBy: 'me',
          createdByName: 'You',
          available: true,
          createdAt: new Date().toISOString(),
        },
      ]);
    },
    [spaceId, pins],
  );

  const onCopy = useCallback(async () => {
    if (!invite) return;
    await Clipboard.setStringAsync(invite);
    setCopied(true);
  }, [invite]);

  const resolveDuplicate = useCallback(
    async (id: string, merge: boolean) => {
      // Optimistic: the card is gone either way, and leaving it on screen
      // while a request settles makes the tap feel unregistered.
      setDuplicates((current) => current.filter((d) => d.id !== id));
      try {
        await (merge ? repo.mergeDuplicate(spaceId, id) : repo.dismissDuplicate(spaceId, id));
        await load();
      } catch {
        await load();
      }
    },
    [spaceId, load],
  );

  if (error && !space) {
    return (
      <Screen>
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Could not open this Space
          </AppText>
          <AppText variant="caption" tone="muted">
            {error}
          </AppText>
        </Card>
      </Screen>
    );
  }

  // `savesLoading` is a *local* read, not a network one, so this gate costs a
  // frame at most — and it is what makes the default tab decided once rather
  // than corrected in front of the user.
  if (!space || savesLoading) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
      <Reveal index={0}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Back to Spaces"
          onPress={() => router.back()}
          haptic="selection"
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            alignSelf: 'flex-start',
            gap: spacing.xs + 2,
            marginBottom: spacing.md + 2,
          }}
        >
          <Glyph name="layers" size={14} weight={2} />
          <AppText tone="muted" style={{ fontSize: 12.5 }}>
            Spaces
          </AppText>
        </Touchable>
      </Reveal>

      <Reveal index={1} style={{ marginBottom: spacing.lg }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
          <View
            style={{
              width: 40,
              height: 40,
              borderRadius: radius.md,
              backgroundColor: `${spaceIdentity(space).color}26`,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name={spaceIdentity(space).glyph} size={18} weight={2} color={spaceIdentity(space).color} />
          </View>
          <View style={{ flex: 1 }}>
            <AppText variant="title">{space.name}</AppText>
            <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
              {space.saveCount} {space.saveCount === 1 ? 'source' : 'sources'} · {space.memberCount}{' '}
              {space.memberCount === 1 ? 'member' : 'members'} · you are {space.myRole}
            </AppText>
          </View>
          <View style={{ flexDirection: 'row', gap: spacing.sm }}>
            {/* Owner-only: the one destructive action for this Space. A
                member never sees this button at all, per the spec's rule
                that the action must not be reachable by anyone but the
                owner — not just rejected server-side if they try. */}
            {isOwner ? (
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Space settings"
                onPress={() => setSpaceMenuOpen((v) => !v)}
                haptic="selection"
                weight="tile"
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: radius.sm,
                  backgroundColor: spaceMenuOpen ? palette.text : palette.surface,
                  borderWidth: 1,
                  borderColor: spaceMenuOpen ? 'transparent' : palette.border,
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Glyph
                  name="settings"
                  size={16}
                  weight={2}
                  color={spaceMenuOpen ? palette.background : undefined}
                />
              </Touchable>
            ) : null}
            {/* The frictionless-add path: opening Capture from here pre-targets
                this Space (`CaptureSheet` reads `spaceId`), so a save made while
                looking at a Space lands in it directly instead of needing a
                second trip through `AddToSpaceSheet` afterwards. */}
            {canEdit ? (
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={`Add to ${space.name}`}
                onPress={() => router.push({ pathname: '/capture', params: { spaceId } })}
                haptic="selection"
                weight="tile"
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: radius.sm,
                  backgroundColor: palette.surface,
                  borderWidth: 1,
                  borderColor: palette.border,
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Glyph name="plus" size={16} weight={2} color={palette.accent} />
              </Touchable>
            ) : null}
          </View>
        </View>
      </Reveal>

      {spaceMenuOpen && isOwner ? (
        <Reveal index={2} style={{ marginBottom: spacing.lg }}>
          <Card radius={radius.md} padding={0} style={{ paddingHorizontal: spacing.md }}>
            <Touchable
              accessibilityRole="button"
              accessibilityLabel={`Delete ${space.name}`}
              onPress={openDeleteModal}
              haptic="medium"
              style={{ paddingVertical: spacing.md }}
            >
              <AppText variant="label" style={{ fontSize: 14, fontWeight: '600', color: palette.danger }}>
                Delete Space
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}

      {duplicates.length > 0 ? (
        <Reveal index={2}>
          {duplicates.map((d) => (
            <DuplicateCard
              key={d.id}
              suggestion={d}
              canAct={!!canEdit}
              onMerge={() => resolveDuplicate(d.id, true)}
              onDismiss={() => resolveDuplicate(d.id, false)}
            />
          ))}
        </Reveal>
      ) : null}

      <Reveal index={3} style={{ marginBottom: spacing.xl }}>
        <Segmented
          options={[
            { value: 'overview', label: 'Overview' },
            { value: 'sources', label: 'Sources' },
            { value: 'people', label: 'People' },
            { value: 'activity', label: 'Activity' },
          ]}
          value={tab}
          onChange={setPicked}
        />
      </Reveal>

      {tab === 'overview' ? (
        <Reveal key="overview">
          {overview.sourceCount === 0 ? (
            <Card>
              <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
                Nothing to build on yet
              </AppText>
              <AppText variant="caption" tone="muted" style={{ marginBottom: canEdit ? spacing.md : 0 }}>
                {canEdit
                  ? 'Everything anyone saves in here gets pulled together on this tab.'
                  : 'Once an editor adds something, what the group is collecting shows up here.'}
              </AppText>
              {canEdit ? (
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel={`Add something to ${space.name}`}
                  onPress={() => router.push({ pathname: '/capture', params: { spaceId } })}
                  haptic="selection"
                  style={{
                    alignSelf: 'flex-start',
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.sm,
                    paddingVertical: spacing.smd,
                    paddingHorizontal: spacing.md,
                    borderRadius: radius.sm,
                    borderWidth: 1,
                    borderColor: palette.border,
                    backgroundColor: palette.surface,
                  }}
                >
                  <Glyph name="plus" size={14} />
                  <AppText variant="bodySmall">Add something</AppText>
                </Touchable>
              ) : null}
            </Card>
          ) : (
            <>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm }}>
                {overview.entityCount > 0 ? (
                  <StatTile value={overview.entityCount} label={entityLabel} />
                ) : null}
                <StatTile
                  value={overview.sourceCount}
                  label={overview.sourceCount === 1 ? 'source' : 'sources'}
                />
                <StatTile
                  value={overview.memberCount}
                  label={overview.memberCount === 1 ? 'person' : 'people'}
                />
                {overview.workingCount > 0 ? (
                  <StatTile value={overview.workingCount} label="processing" />
                ) : null}
              </View>

              {/* S4: pinned saves lead the tab, because that is what a pin
                  means. A pinned *collection* does not get a card of its own —
                  it sorts to the top of the list below instead, since it
                  already has a row there and two rows for one thing is the
                  no-listing-twice rule K6 established. */}
              {pinnedSaves.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>Pinned</SectionLabel>
                  <View style={{ gap: spacing.sm }}>
                    {pinnedSaves.map((pin) => (
                      <PinCard
                        key={pin.id}
                        pin={pin}
                        canEdit={!!canEdit}
                        onOpen={() =>
                          router.push({ pathname: '/save/[id]', params: { id: pin.subject } })
                        }
                        onUnpin={() => togglePin(pin.kind, pin.subject)}
                      />
                    ))}
                  </View>
                </View>
              ) : null}

              {overview.collections.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>What the group is collecting</SectionLabel>
                  <View style={{ gap: spacing.sm }}>
                    {sortedCollections.map((summary) => (
                      <CollectionSummaryRow
                        key={summary.type}
                        summary={summary}
                        doneCount={doneByCollection.get(summary.type) ?? 0}
                        pinned={pinnedCollections.has(summary.type)}
                        canEdit={!!canEdit}
                        onTogglePin={() => togglePin('collection', summary.type, summary.name)}
                        onPress={() =>
                          router.push({
                            pathname: '/space/[id]/collection/[nodeId]',
                            params: { id: spaceId, nodeId: summary.type },
                          })
                        }
                      />
                    ))}
                  </View>
                </View>
              ) : null}

              {/* S4: the shared shopping list. Shown only once it has something
                  on it — a Space with no recipes in it does not need to be told
                  it could have a shopping list, and an empty row promising a
                  feature is the kind of scaffolding the sample-content sweep
                  removed everywhere else. */}
              {sharedList && sharedList.items.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>Shared shopping list</SectionLabel>
                  <Card padding={0} radius={radius.md}>
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel={`Shared shopping list, ${outstandingCount} of ${sharedList.items.length} still to buy`}
                      onPress={() =>
                        router.push({
                          pathname: '/space/[id]/shopping-list',
                          params: { id: spaceId },
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
                      <Glyph name="utensils" size={16} weight={2} color={palette.accent} />
                      <View style={{ flex: 1 }}>
                        <AppText variant="cardTitle">
                          {outstandingCount} still to buy
                        </AppText>
                        <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
                          {sharedList.items.length}{' '}
                          {sharedList.items.length === 1 ? 'line' : 'lines'}, from everyone&rsquo;s
                          recipes in here
                        </AppText>
                      </View>
                      <View style={{ transform: [{ scaleX: -1 }] }}>
                        <Glyph name="chevron" size={14} color={palette.textFaint} />
                      </View>
                    </Touchable>
                  </Card>
                </View>
              ) : null}

              {/* S2: where everyone has got to. Absent, not zeroed, when the
                  request has not landed or nobody has marked anything — an
                  empty progress block is a claim that nothing is happening,
                  which is different from not knowing. */}
              {knowledge && knowledge.members.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>Where everyone is</SectionLabel>
                  <Card>
                    {knowledge.members.map((member) => (
                      <MemberProgressRow key={member.userId} progress={member} />
                    ))}
                    <AppText variant="caption" tone="muted" style={{ marginTop: spacing.sm }}>
                      Progress is shared with everyone in this Space.
                    </AppText>
                  </Card>
                </View>
              ) : null}

              {knowledge && knowledge.recentComments.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>Recent discussion</SectionLabel>
                  <Card>
                    {knowledge.recentComments.map((comment) => (
                      <CommentRow
                        key={comment.id}
                        comment={comment}
                        onPress={
                          comment.saveId
                            ? () =>
                                router.push({
                                  pathname: '/save/[id]',
                                  params: { id: comment.saveId as string },
                                })
                            : undefined
                        }
                      />
                    ))}
                  </Card>
                </View>
              ) : null}

              {overview.types.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>What&rsquo;s in here</SectionLabel>
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}>
                    {overview.types.map((entry) => (
                      <TypeChip key={entry.type} type={entry.type} count={entry.count} />
                    ))}
                  </View>
                </View>
              ) : null}

              {/* Honest about the one thing the counts above can't be: a save
                  that failed contributed nothing to any of them. */}
              {overview.failedCount > 0 ? (
                <AppText variant="caption" tone="muted" style={{ marginTop: spacing.lg }}>
                  {overview.failedCount} {overview.failedCount === 1 ? 'source' : 'sources'} could not
                  be read — see the Sources tab.
                </AppText>
              ) : null}
            </>
          )}
        </Reveal>
      ) : null}

      {tab === 'sources' ? (
        <Reveal key="sources">
          {saves.length === 0 ? (
            <Card>
              <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
                Nothing here yet
              </AppText>
              <AppText variant="caption" tone="muted" style={{ marginBottom: canEdit ? spacing.md : 0 }}>
                {canEdit
                  ? 'Save something into this Space from the Capture sheet.'
                  : 'You can read and comment here. An editor can add sources.'}
              </AppText>
              {canEdit ? (
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel={`Add a source to ${space.name}`}
                  onPress={() => router.push({ pathname: '/capture', params: { spaceId } })}
                  haptic="selection"
                  style={{
                    alignSelf: 'flex-start',
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.sm,
                    paddingVertical: spacing.smd,
                    paddingHorizontal: spacing.md,
                    borderRadius: radius.sm,
                    borderWidth: 1,
                    borderColor: palette.border,
                    backgroundColor: palette.surface,
                  }}
                >
                  <Glyph name="plus" size={14} />
                  <AppText variant="bodySmall">Add a source</AppText>
                </Touchable>
              ) : null}
            </Card>
          ) : (
            <View style={{ gap: spacing.sm }}>
              {/* SaveCard already falls back to a flat row for anything still
                  processing or without a bespoke layout, so the Space feed
                  needs no branch of its own. */}
              {saves.map((save) => (
                <View key={save.id}>
                  <SaveCard
                    save={save}
                    onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
                  />
                  {/* S4: where a "current program" is actually chosen. Under the
                      card rather than inside it, because `SaveCard` is shared
                      with Home, Library and search, and a Space-only action
                      does not belong in the component all four render. */}
                  {canEdit ? (
                    <Touchable
                      accessibilityRole="button"
                      accessibilityState={{ selected: pinnedSaveIds.has(save.id) }}
                      accessibilityLabel={
                        pinnedSaveIds.has(save.id)
                          ? `Unpin ${saveTitle(save)}`
                          : `Pin ${saveTitle(save)} to the top of this Space`
                      }
                      onPress={() =>
                        togglePin('save', save.id, saveTitle(save))
                      }
                      haptic="selection"
                      style={{
                        flexDirection: 'row',
                        alignItems: 'center',
                        alignSelf: 'flex-start',
                        gap: spacing.xs,
                        paddingVertical: spacing.xs,
                        paddingHorizontal: spacing.xs,
                      }}
                    >
                      <Glyph
                        name="pin"
                        size={12}
                        weight={2}
                        color={pinnedSaveIds.has(save.id) ? palette.accent : palette.textFaint}
                      />
                      <AppText
                        variant="caption"
                        tone={pinnedSaveIds.has(save.id) ? 'accent' : 'muted'}
                        style={{ fontSize: 11 }}
                      >
                        {pinnedSaveIds.has(save.id) ? 'Pinned' : 'Pin'}
                      </AppText>
                    </Touchable>
                  ) : null}
                </View>
              ))}
            </View>
          )}
        </Reveal>
      ) : null}

      {tab === 'people' ? (
        <Reveal key="people">
          {canEdit ? (
            <View style={{ marginBottom: spacing.xl }}>
              <SectionLabel>Invite</SectionLabel>
              {invite ? (
                <Card>
                  <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xs }}>
                    Share this code. Anyone who has it can join as an editor.
                  </AppText>
                  <AppText
                    variant="cardTitle"
                    selectable
                    style={{ fontFamily: 'monospace', marginBottom: spacing.smd }}
                  >
                    {invite}
                  </AppText>
                  <Touchable
                    accessibilityRole="button"
                    onPress={onCopy}
                    haptic="selection"
                    style={{
                      alignSelf: 'flex-start',
                      paddingVertical: spacing.xs + 2,
                      paddingHorizontal: spacing.md,
                      borderRadius: radius.sm,
                      backgroundColor: palette.accent,
                    }}
                  >
                    <AppText variant="caption" style={{ color: palette.onAccent }}>
                      {copied ? 'Copied' : 'Copy code'}
                    </AppText>
                  </Touchable>
                </Card>
              ) : (
                <Touchable
                  accessibilityRole="button"
                  onPress={onInvite}
                  haptic="selection"
                  style={{
                    alignSelf: 'flex-start',
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.sm,
                    paddingVertical: spacing.smd,
                    paddingHorizontal: spacing.md,
                    borderRadius: radius.sm,
                    borderWidth: 1,
                    borderColor: palette.border,
                    backgroundColor: palette.surface,
                  }}
                >
                  <Glyph name="link" size={14} />
                  <AppText variant="bodySmall">Create an invite link</AppText>
                </Touchable>
              )}
            </View>
          ) : null}

          <SectionLabel>Members</SectionLabel>
          <View style={{ gap: spacing.sm }}>
            {members.map((member) => (
              <Card key={member.userId} radius={radius.md}>
                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
                    <Avatar id={member.userId} name={member.displayName} size={30} />
                    <AppText variant="bodySmall">{member.displayName ?? 'A Weavr user'}</AppText>
                  </View>
                  <AppText variant="caption" tone="muted">
                    {member.role}
                  </AppText>
                </View>
              </Card>
            ))}
          </View>

          {/*
            The S2 disclosure decision, stated rather than discovered.

            `entity_states` is per `(user_id, entity_key)` and global — there is
            no Space dimension, because completing Your Name is a fact about the
            person, not about a room they happen to be in. The consequence is
            real and worth naming: something marked watched from a private
            library shows as watched in any Space whose knowledge contains it.
            Sharing progress is what a shared watchlist is *for*, and the
            alternative — forking state per Space — fragments the single fact
            the entity layer exists to keep whole. So it is shown, and said.
          */}
          <AppText variant="caption" tone="muted" style={{ marginTop: spacing.lg }}>
            Everyone here can see what you&rsquo;ve marked watched, visited or done on this
            Space&rsquo;s shared lists — including things you marked from your own library.
          </AppText>
        </Reveal>
      ) : null}

      {tab === 'activity' ? (
        <Reveal key="activity">
          {activity.length === 0 ? (
            <Card>
              <AppText variant="caption" tone="muted">
                Nothing has happened here yet.
              </AppText>
            </Card>
          ) : (
            <Card>
              {activity.map((entry) => (
                <ActivityRow key={entry.id} entry={entry} />
              ))}
            </Card>
          )}
        </Reveal>
      ) : null}

      <View style={{ height: spacing.lg }} />

      {/* ---- Delete Space confirmation (cross-platform, works on Android) ---- */}
      <Modal
        visible={deleteModalOpen}
        transparent
        animationType="fade"
        onRequestClose={() => !deleteBusy && setDeleteModalOpen(false)}
      >
        <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <Pressable
            style={{
              flex: 1,
              backgroundColor: 'rgba(0,0,0,0.55)',
              justifyContent: 'center',
              paddingHorizontal: spacing.xl,
            }}
            onPress={() => !deleteBusy && setDeleteModalOpen(false)}
          >
            <Pressable
              onPress={() => {}}
              style={{ backgroundColor: palette.surface, borderRadius: radius.lg, padding: spacing.xl, gap: spacing.md }}
            >
              <AppText variant="cardTitle">Delete &ldquo;{space.name}&rdquo;?</AppText>
              <AppText variant="caption" tone="muted">
                This will permanently delete this Space and its shared content.{'\n'}This action cannot be undone.
              </AppText>

              {requiresTypedConfirmation ? (
                <View style={{ gap: spacing.xs }}>
                  <AppText variant="caption" tone="muted">
                    Type <AppText variant="caption" style={{ fontWeight: '700' }}>{space.name}</AppText> to confirm.
                  </AppText>
                  <TextInput
                    value={deleteConfirmText}
                    onChangeText={(t) => {
                      setDeleteConfirmText(t);
                      setDeleteError(null);
                    }}
                    placeholder={space.name}
                    placeholderTextColor={palette.textFaint}
                    autoCapitalize="none"
                    autoCorrect={false}
                    editable={!deleteBusy}
                    style={{
                      color: palette.text,
                      fontSize: 16,
                      paddingVertical: spacing.md,
                      paddingHorizontal: spacing.md,
                      borderRadius: radius.sm,
                      backgroundColor: palette.surfaceVariant,
                    }}
                  />
                </View>
              ) : null}

              {deleteError ? (
                <AppText variant="caption" style={{ color: palette.danger }}>
                  {deleteError}
                </AppText>
              ) : null}

              <View style={{ flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs }}>
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel="Cancel delete Space"
                  onPress={() => setDeleteModalOpen(false)}
                  disabled={deleteBusy}
                  style={{
                    flex: 1,
                    alignItems: 'center',
                    paddingVertical: spacing.md,
                    borderRadius: radius.pill,
                    backgroundColor: palette.surfaceVariant,
                  }}
                >
                  <AppText variant="label" style={{ fontSize: 14 }}>
                    Cancel
                  </AppText>
                </Touchable>
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel="Confirm delete Space"
                  onPress={() => void confirmDeleteSpace()}
                  disabled={deleteBusy || !deleteConfirmMatches}
                  haptic="medium"
                  baseOpacity={deleteBusy || !deleteConfirmMatches ? 0.45 : 1}
                  style={{
                    flex: 1,
                    alignItems: 'center',
                    paddingVertical: spacing.md,
                    borderRadius: radius.pill,
                    backgroundColor: palette.danger,
                  }}
                >
                  {deleteBusy ? (
                    <ActivityIndicator color="#ffffff" />
                  ) : (
                    <AppText variant="label" style={{ fontSize: 14, color: '#ffffff' }}>
                      Delete Space
                    </AppText>
                  )}
                </Touchable>
              </View>
            </Pressable>
          </Pressable>
        </KeyboardAvoidingView>
      </Modal>
    </Screen>
  );
}
