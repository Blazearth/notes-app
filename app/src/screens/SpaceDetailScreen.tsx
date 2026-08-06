import * as Clipboard from 'expo-clipboard';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
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
import { relativeTime } from '@/saves/format';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { useTheme } from '@/theme/ThemeProvider';

type TabValue = 'saves' | 'people' | 'activity';

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

export function SpaceDetailScreen({ spaceId }: { spaceId: string }) {
  const { palette, radius, spacing } = useTheme();
  const router = useRouter();

  const [tab, setTab] = useState<TabValue>('saves');
  const [space, setSpace] = useState<Space | null>(null);
  const [saves, setSaves] = useState<SaveResponse[]>([]);
  const [members, setMembers] = useState<SpaceMember[]>([]);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [duplicates, setDuplicates] = useState<DuplicateSuggestion[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [invite, setInvite] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    try {
      // One await of everything rather than a waterfall: the tabs are all
      // visible within one tap of each other, and four sequential round trips
      // on a phone network is the difference between instant and sluggish.
      const [s, sv, m, a, d] = await Promise.all([
        repo.getSpace(spaceId),
        repo.listSpaceSaves(spaceId),
        repo.listSpaceMembers(spaceId),
        repo.getSpaceActivity(spaceId),
        repo.listDuplicates(spaceId),
      ]);
      setSpace(s);
      setSaves(sv);
      setMembers(m);
      setActivity(a);
      setDuplicates(d);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load this Space.');
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

  if (!space) {
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
              {space.saveCount} {space.saveCount === 1 ? 'save' : 'saves'} · {space.memberCount}{' '}
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
            { value: 'saves', label: 'Saves' },
            { value: 'people', label: 'People' },
            { value: 'activity', label: 'Activity' },
          ]}
          value={tab}
          onChange={setTab}
        />
      </Reveal>

      {tab === 'saves' ? (
        <Reveal key="saves">
          {saves.length === 0 ? (
            <Card>
              <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
                Nothing here yet
              </AppText>
              <AppText variant="caption" tone="muted">
                {canEdit
                  ? 'Save something into this Space from the Capture sheet.'
                  : 'You can read and comment here. An editor can add saves.'}
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
