import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { RefreshControl, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import type { SaveResponse, Space, SpaceMember } from '@/api/types';
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

  const [spaces, setSpaces] = useState<Space[] | null>(null);
  const [membersById, setMembersById] = useState<Record<string, SpaceMember[]>>({});
  const [recentById, setRecentById] = useState<Record<string, SaveResponse[]>>({});
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');

  const load = useCallback(async () => {
    let list: Space[];
    try {
      list = await repo.listSpaces();
      setSpaces(list);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load your Spaces.');
      return;
    }

    // Card enrichment (avatars, recent saves) is a second, non-fatal round of
    // requests — a slow or broken one degrades a card to counts-only rather
    // than blocking the list that just loaded successfully.
    try {
      const pairs = await Promise.all(
        list.map(async (space) => {
          const [members, recent] = await Promise.all([
            repo.listSpaceMembers(space.id),
            repo.listSpaceSaves(space.id, 0, 3),
          ]);
          return [space.id, members, recent] as const;
        }),
      );
      const nextMembers: Record<string, SpaceMember[]> = {};
      const nextRecent: Record<string, SaveResponse[]> = {};
      for (const [id, members, recent] of pairs) {
        nextMembers[id] = members;
        nextRecent[id] = recent;
      }
      setMembersById(nextMembers);
      setRecentById(nextRecent);
    } catch {
      // Swallowed — see comment above.
    }
  }, []);

  // Refetched on focus rather than once on mount: creating or joining a Space
  // happens on a sheet stacked over this screen, and coming back to a stale
  // list reads as the action having failed.
  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load]),
  );

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

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

  const openCreate = useCallback(() => router.push('/space/create'), [router]);
  const openJoin = useCallback(() => router.push('/space/join'), [router]);

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
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

      {spaces === null && !error ? (
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

      {spaces && spaces.length === 0 && !error ? <EmptyState onCreate={openCreate} onJoin={openJoin} /> : null}

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
