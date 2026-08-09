import * as Clipboard from 'expo-clipboard';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import { useLive, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import type {
  ActivityEntry,
  DuplicateSuggestion,
  SaveResponse,
  Space,
  SpaceMember,
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
import { relativeTime } from '@/saves/format';
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
 * **Not tappable, on purpose.** The merged entity list behind this row is S2,
 * and the existing `/collection/[type]` screen renders the *viewer's whole
 * library*, not this Space — sending a Space's row there would show a
 * different set of entities under the Space's heading. A row that goes nowhere
 * is better than one that goes somewhere wrong; it becomes tappable when the
 * Space-scoped list exists to receive it.
 */
function CollectionSummaryRow({ summary }: { summary: SpaceOverview['collections'][number] }) {
  const { radius, spacing } = useTheme();
  const typeMeta = saveTypeMeta(summary.type);
  const collMeta = collectionTypeMeta(summary.type);
  const counts = [
    `${summary.entityCount} ${collMeta.entityNoun(summary.entityCount)}`,
    `${summary.sourceCount} ${summary.sourceCount === 1 ? 'source' : 'sources'}`,
  ].join(' · ');

  return (
    <Card radius={radius.md}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
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
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [invite, setInvite] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

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
    const [detail, a, d] = await Promise.allSettled([
      sync.pullSpace(spaceId),
      repo.getSpaceActivity(spaceId),
      repo.listDuplicates(spaceId),
    ] as const);
    if (a.status === 'fulfilled') setActivity(a.value);
    if (d.status === 'fulfilled') setDuplicates(d.value);
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

  // Derived from saves already in the store — no request, no AI. See
  // `@/spaces/spaceOverview` for what S0 deliberately stops short of.
  const overview = useMemo(
    () => buildSpaceOverview({ saves, memberCount: space?.memberCount ?? 0 }),
    [saves, space?.memberCount],
  );
  const tab: TabValue = picked ?? spaceDefaultTab(overview);
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
        </View>
      </Reveal>

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
              <AppText variant="caption" tone="muted">
                {canEdit
                  ? 'Everything anyone saves in here gets pulled together on this tab.'
                  : 'Once an editor adds something, what the group is collecting shows up here.'}
              </AppText>
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

              {overview.collections.length > 0 ? (
                <View style={{ marginTop: spacing.xl }}>
                  <SectionLabel>What the group is collecting</SectionLabel>
                  <View style={{ gap: spacing.sm }}>
                    {overview.collections.map((summary) => (
                      <CollectionSummaryRow key={summary.type} summary={summary} />
                    ))}
                  </View>
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
              <AppText variant="caption" tone="muted">
                {canEdit
                  ? 'Save something into this Space from the Capture sheet.'
                  : 'You can read and comment here. An editor can add sources.'}
              </AppText>
            </Card>
          ) : (
            <View style={{ gap: spacing.sm }}>
              {/* SaveCard already falls back to a flat row for anything still
                  processing or without a bespoke layout, so the Space feed
                  needs no branch of its own. */}
              {saves.map((save) => (
                <SaveCard
                  key={save.id}
                  save={save}
                  onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
                />
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
    </Screen>
  );
}
