import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { RefreshControl, TextInput, View } from 'react-native';

import type { SaveResponse, Space, SpaceMember } from '@/api/types';
import { useLive, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import { useTaskStatus } from '@/local/useSync';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { Skeleton } from '@/components/Skeleton';
import { SpaceCard } from '@/components/SpaceCard';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * The Spaces tab.
 *
 * Rebuilt from a creation-form-first layout to a browse-first one: the earlier
 * version put "New Space" and "Join with a code" permanently open above the
 * list, which is backwards for how people actually use this screen — you walk
 * into Spaces you're already in far more often than you make a new one. Both
 * actions move to header buttons that open a sheet (`CreateSpaceSheet`,
 * `JoinSpaceSheet`) instead, and the list itself becomes a `SpaceCard` per
 * Space rather than a flat settings-style row.
 */

const SEARCH_THRESHOLD = 4;

/** Stable identities for "the store has nothing yet" — see `useLiveValue`. */
const EMPTY_MEMBERS: Record<string, SpaceMember[]> = {};
const EMPTY_RECENT: Record<string, SaveResponse[]> = {};

function HeaderAction({
  glyph,
  label,
  onPress,
}: {
  glyph: 'plus' | 'download';
  label: string;
  onPress: () => void;
}) {
  const { palette, radius, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      weight="tile"
      onPress={onPress}
      haptic="selection"
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
      <Glyph name={glyph} size={icon.sm} />
    </Touchable>
  );
}

function SpaceCardSkeleton() {
  const { radius, spacing, layout } = useTheme();
  return (
    <Card radius={radius.lg} padding={layout.cardPadding}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
        <Skeleton width={44} height={44} radius={radius.md} />
        <View style={{ flex: 1, gap: spacing.xs }}>
          <Skeleton width="60%" height={14} />
          <Skeleton width="40%" height={11} />
        </View>
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.md }}>
        <Skeleton width={70} height={22} radius={radius.pill} />
        <Skeleton width={60} height={11} />
      </View>
    </Card>
  );
}

/** No Spaces at all yet — a welcome, not a blank form. */
function EmptyState({ onCreate, onJoin }: { onCreate: () => void; onJoin: () => void }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <Card radius={radius.lg} style={{ alignItems: 'center', paddingVertical: spacing.xxl }}>
      <View
        style={{
          width: 64,
          height: 64,
          borderRadius: radius.pill,
          backgroundColor: palette.accentContainer,
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: spacing.lg,
        }}
      >
        <Glyph name="layers" size={26} weight={2} color={palette.onAccentContainer} />
      </View>
      <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
        Collaborate with anyone
      </AppText>
      <AppText variant="caption" tone="muted" style={{ textAlign: 'center', marginBottom: spacing.xl, maxWidth: 240 }}>
        Create a Space for a trip, a project or a household, or join one someone shared with you.
      </AppText>
      <View style={{ flexDirection: 'row', gap: spacing.sm }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Create Space"
          onPress={onCreate}
          haptic="selection"
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.xs,
            paddingVertical: spacing.smd,
            paddingHorizontal: spacing.lg,
            borderRadius: radius.sm,
            backgroundColor: palette.accent,
          }}
        >
          <Glyph name="plus" size={14} weight={2.5} color={palette.onAccent} />
          <AppText variant="caption" style={{ color: palette.onAccent, fontWeight: '600' }}>
            Create Space
          </AppText>
        </Touchable>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Join Space"
          onPress={onJoin}
          haptic="selection"
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.xs,
            paddingVertical: spacing.smd,
            paddingHorizontal: spacing.lg,
            borderRadius: radius.sm,
            borderWidth: 1,
            borderColor: palette.border,
          }}
        >
          <Glyph name="download" size={14} color={palette.textMuted} />
          <AppText variant="caption">Join Space</AppText>
        </Touchable>
      </View>
    </Card>
  );
}

export function SpacesScreen() {
  const { palette, radius, spacing } = useTheme();
  const router = useRouter();

  const [query, setQuery] = useState('');

  // The list, the avatar stacks and the recent-save chips all read the local
  // store. What used to be here — one request for the Spaces plus **two per
  // Space** for members and recent saves, re-run on every focus — is now the
  // sync engine's business (`sync.syncSpaceMembers`), off the render path
  // entirely. The card paints from cache instantly and its avatars fill in.
  const { data: spaces, loading } = useLive<Space[]>(['spaces'], (store) => store.readSpaces());
  const membersById = useLiveValue<Record<string, SpaceMember[]>>(
    ['space_members'],
    (store) => store.readAllSpaceMembers(),
    EMPTY_MEMBERS,
  );
  // Derived rather than fetched: a save carries its own `spaceId`, and the
  // whole library is local, so "the three most recent saves in this Space" is
  // a group-by over data already on hand.
  const recentById = useLiveValue<Record<string, SaveResponse[]>>(
    ['saves'],
    async (store) => {
      const bySpace: Record<string, SaveResponse[]> = {};
      for (const save of await store.readFeed()) {
        if (!save.spaceId) continue;
        const bucket = (bySpace[save.spaceId] ??= []);
        if (bucket.length < 3) bucket.push(save);
      }
      return bySpace;
    },
    EMPTY_RECENT,
  );

  const task = useTaskStatus('delta', (spaces?.length ?? 0) > 0);
  const error = task.error && !spaces?.length ? task.error.message : null;

  // Still re-synced on focus, and still for the original reason: creating or
  // joining a Space happens on a sheet stacked over this screen. The difference
  // is that it no longer blocks anything — the list is already on screen from
  // the store, and both sheets write their new Space into it directly, so this
  // is a background reconciliation rather than the thing that fills the page.
  useFocusEffect(
    useCallback(() => {
      void sync.syncSpaces().then(() => sync.syncSpaceMembers());
    }, []),
  );

  const onRefresh = useCallback(async () => {
    await sync.syncSpaces();
    await sync.syncSpaceMembers();
  }, []);

  const sorted = useMemo(
    () =>
      spaces
        ? [...spaces].sort(
            (a, b) => new Date(b.lastActivityAt).getTime() - new Date(a.lastActivityAt).getTime(),
          )
        : [],
    [spaces],
  );

  const filtered = useMemo(() => {
    const trimmed = query.trim().toLowerCase();
    if (!trimmed) return sorted;
    return sorted.filter((s) => s.name.toLowerCase().includes(trimmed));
  }, [sorted, query]);

  const showSkeleton = loading || (spaces?.length === 0 && task.firstLoad);

  const openCreate = useCallback(() => router.push('/space/create'), [router]);
  const openJoin = useCallback(() => router.push('/space/join'), [router]);

  return (
    <Screen refreshControl={<RefreshControl refreshing={task.running} onRefresh={onRefresh} />}>
      <Reveal index={0} style={{ marginBottom: spacing.lg }}>
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' }}>
          <View style={{ flex: 1, paddingRight: spacing.md }}>
            <AppText variant="title">Spaces</AppText>
            <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
              Shared collections. Anything you save into one is visible to everybody in it.
            </AppText>
          </View>
          <View style={{ flexDirection: 'row', gap: spacing.sm }}>
            <HeaderAction glyph="download" label="Join Space" onPress={openJoin} />
            <HeaderAction glyph="plus" label="Create Space" onPress={openCreate} />
          </View>
        </View>
      </Reveal>

      {spaces && spaces.length > SEARCH_THRESHOLD ? (
        <Reveal index={1} style={{ marginBottom: spacing.lg }}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing.sm,
              backgroundColor: palette.surface,
              borderWidth: 1,
              borderColor: palette.border,
              borderRadius: radius.sm,
              paddingHorizontal: spacing.md,
              paddingVertical: spacing.smd,
            }}
          >
            <Glyph name="search" size={14} color={palette.textFaint} />
            <TextInput
              value={query}
              onChangeText={setQuery}
              placeholder="Find a Space"
              placeholderTextColor={palette.textFaint}
              style={{ flex: 1, color: palette.text, fontSize: 14, padding: 0 }}
              autoCapitalize="none"
              returnKeyType="search"
            />
          </View>
        </Reveal>
      ) : null}

      {/* The skeleton is now a genuinely-first-run state, not a per-visit one:
          with anything cached the list is already painted above, and `firstLoad`
          is false the moment the Spaces sync has landed once on this install. */}
      {showSkeleton ? (
        <View style={{ gap: spacing.sm }}>
          {[0, 1, 2].map((i) => (
            <SpaceCardSkeleton key={i} />
          ))}
        </View>
      ) : null}

      {error ? (
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Could not load Spaces
          </AppText>
          <AppText variant="caption" tone="muted">
            {error}
          </AppText>
        </Card>
      ) : null}

      {!showSkeleton && spaces?.length === 0 && !error ? (
        <EmptyState onCreate={openCreate} onJoin={openJoin} />
      ) : null}

      {spaces && spaces.length > 0 && filtered.length === 0 ? (
        <Card>
          <AppText variant="caption" tone="muted">
            No Spaces match “{query}”.
          </AppText>
        </Card>
      ) : null}

      <View style={{ gap: spacing.sm }}>
        {filtered.map((space, index) => (
          <Reveal key={space.id} index={index}>
            <SpaceCard
              space={space}
              members={membersById[space.id]}
              recentSaves={recentById[space.id]}
              onPress={() => router.push({ pathname: '/space/[id]', params: { id: space.id } })}
            />
          </Reveal>
        ))}
      </View>

      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
