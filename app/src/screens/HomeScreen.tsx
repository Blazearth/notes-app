import { useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, View } from 'react-native';

import { listSavesByLifecycle, listSpaces } from '@/api/client';
import type { SaveResponse, Space } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { HatchThumb } from '@/components/HatchThumb';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { GREETING_NAME, WEEKLY_DIGEST } from '@/data/sampleContent';
import { usePreferences } from '@/prefs/PreferencesProvider';
import { STATUS_LABELS, saveTitle } from '@/saves/format';
import { useSaves } from '@/saves/SavesProvider';
import { useTheme } from '@/theme/ThemeProvider';

function greetingForHour(hour: number): string {
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

/** Horizontally scrolling rail that bleeds into the screen gutter. */
function Rail({ children }: { children: React.ReactNode }) {
  const { layout, spacing } = useTheme();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={{ marginHorizontal: -layout.screenGutter }}
      contentContainerStyle={{ paddingHorizontal: layout.screenGutter, gap: spacing.md }}
    >
      {children}
    </ScrollView>
  );
}

/**
 * How far along a lifecycle stage reads as a bar. `saved` never appears here —
 * the rail is what you have started, not what you own.
 */
const LIFECYCLE_PROGRESS: Record<string, { fraction: number; label: string }> = {
  planned: { fraction: 0.33, label: 'Planned' },
  started: { fraction: 0.66, label: 'In progress' },
  completed: { fraction: 1, label: 'Done' },
};

function ContinueCard({ save, onPress }: { save: SaveResponse; onPress: () => void }) {
  const { palette, radius, spacing } = useTheme();
  const progress = LIFECYCLE_PROGRESS[save.lifecycleStatus ?? 'saved'];

  return (
    <Card padding={0} radius={radius.lg} style={{ width: 156, overflow: 'hidden' }}>
      <Touchable accessibilityRole="button" onPress={onPress} haptic="selection">
        <HatchThumb label={save.knowledgeType ?? 'save'} height={90} radius={0} />
        <View style={{ padding: spacing.smd }}>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }} numberOfLines={1}>
            {saveTitle(save)}
          </AppText>
          <View
            style={{
              height: 4,
              borderRadius: 2,
              backgroundColor: palette.border,
              marginBottom: spacing.xs,
            }}
          >
            <View
              style={{
                width: `${Math.round((progress?.fraction ?? 0) * 100)}%`,
                height: '100%',
                borderRadius: 2,
                backgroundColor: palette.accent,
              }}
            />
          </View>
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {progress?.label ?? 'Saved'}
          </AppText>
        </View>
      </Touchable>
    </Card>
  );
}

/** A small status pill for saves the pipeline has not finished. */
function StatusPill({ label, tint }: { label: string; tint: string }) {
  const { radius, spacing } = useTheme();
  return (
    <View
      style={{
        paddingVertical: 3,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.pill,
        backgroundColor: tint,
      }}
    >
      <AppText variant="caption" style={{ fontSize: 9.5, color: '#FFFFFF' }}>
        {label}
      </AppText>
    </View>
  );
}

/**
 * The live feed section.
 *
 * All four states are real here, because with no job runner yet the interesting
 * ones are the common ones: a save is created and then sits at `processing`
 * indefinitely, and an unreachable API is the single most likely thing to happen
 * during development.
 */
function RecentlyCaptured() {
  const { palette, spacing } = useTheme();
  const { saves, status, error, refresh } = useSaves();
  const router = useRouter();

  if (status === 'loading') {
    return (
      <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
        <ActivityIndicator color={palette.accent} />
      </View>
    );
  }

  if (status === 'error') {
    return (
      <Card>
        <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
          {error?.kind === 'network' ? "Can't reach Weavr" : 'Could not load your saves'}
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
    );
  }

  if (saves.length === 0) {
    return (
      <Card>
        <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
          Nothing saved yet
        </AppText>
        <AppText variant="caption" tone="muted">
          Tap + and paste a link, or share something into Weavr from another app.
        </AppText>
      </Card>
    );
  }

  const tintFor = (status_: string) =>
    status_ === 'failed' ? palette.danger : status_ === 'pending' ? palette.warning : palette.accent;

  return (
    <View style={{ gap: spacing.smd }}>
      {saves.map((save) => (
        <SaveCard
          key={save.id}
          save={save}
          // Tappable whatever the status: a processing or failed save is a
          // legitimate thing to open, and the detail screen explains itself
          // rather than rendering empty.
          onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
          trailing={
            save.status === 'ready' ? undefined : (
              <StatusPill label={STATUS_LABELS[save.status]} tint={tintFor(save.status)} />
            )
          }
        />
      ))}
    </View>
  );
}

export function HomeScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const { prefs } = usePreferences();
  const { refresh, refreshing } = useSaves();
  const router = useRouter();

  const name = prefs.userName.trim() || GREETING_NAME;
  const greeting = greetingForHour(new Date().getHours());

  // Its own request rather than a filter over the feed: the rail wants what
  // was last *touched*, which the server orders by `updated_at`, and the feed
  // is ordered by `created_at` and paged. Filtering the first page client-side
  // would miss anything older than 25 saves.
  const [continueSaves, setContinueSaves] = useState<SaveResponse[]>([]);
  const [spaces, setSpaces] = useState<Space[]>([]);

  useEffect(() => {
    let cancelled = false;
    // Both sections hide themselves when empty, so a failure here degrades to
    // absence rather than to an error card sitting above the feed. `allSettled`
    // so one failing does not blank the other.
    void Promise.allSettled([listSavesByLifecycle(['planned', 'started']), listSpaces()]).then(
      ([rail, mySpaces]) => {
        if (cancelled) return;
        if (rail.status === 'fulfilled') setContinueSaves(rail.value);
        if (mySpaces.status === 'fulfilled') setSpaces(mySpaces.value);
      },
    );
    return () => {
      cancelled = true;
    };
    // `refreshing` flips on every pull-to-refresh, which is also when the
    // user expects this to be current.
  }, [refreshing]);

  return (
    <Screen
      cover
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => void refresh()}
          tintColor={palette.accent}
          colors={[palette.accent]}
          progressBackgroundColor={palette.surface}
        />
      }
    >
      {/* Greeting + avatar */}
      <Reveal
        index={0}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.xl,
        }}
      >
        <View>
          <AppText tone="muted" style={{ fontSize: 13 }}>
            {greeting}
          </AppText>
          <AppText variant="title">{name}</AppText>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
          {/* The shopping list is the payoff of the one Act, so it gets a
              permanent way in rather than only appearing after a conversion. */}
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Shopping list"
            onPress={() => router.push('/shopping-list')}
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
            <Glyph name="fileText" size={icon.sm} />
          </Touchable>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Settings"
            onPress={() => router.push('/settings')}
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
            <Glyph name="settings" size={icon.sm} />
          </Touchable>
        </View>
      </Reveal>

      {/* Ask-or-find bar */}
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
            marginBottom: spacing.xxl - 2,
          }}
        >
          <Glyph name="search" size={16} weight={2} />
          <AppText tone="muted" style={{ fontSize: 14 }}>
            Ask or find anything…
          </AppText>
        </Touchable>
      </Reveal>

      {/* Real now, and absent when there is nothing in flight. The rail used
          to render three invented cards unconditionally; an empty rail is
          honest, three fake ones are not. */}
      {continueSaves.length > 0 ? (
        <Reveal index={2}>
          <SectionLabel>Continue</SectionLabel>
          <View style={{ marginBottom: spacing.xxl - 2 }}>
            <Rail>
              {continueSaves.map((save) => (
                <ContinueCard
                  key={save.id}
                  save={save}
                  onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
                />
              ))}
            </Rail>
          </View>
        </Reveal>
      ) : null}

      <Reveal index={3}>
        <Card variant="accent" padding={spacing.lg} style={{ marginBottom: spacing.xxl - 2 }}>
          {/* On the accent container, not the page — so the label uses the
              container's computed on-colour rather than the accent itself. */}
          <AppText variant="sectionLabel" tone="onAccentContainer" style={{ marginBottom: spacing.sm }}>
            Weekly digest
          </AppText>
          <AppText tone="onAccentContainer">{WEEKLY_DIGEST}</AppText>
        </Card>
      </Reveal>

      {/* Real Spaces now, and hidden when the user is in none — the two
          invented ones that used to sit here were the last fiction on this
          screen. Capped at two: this is a glance, and the Spaces tab is one
          tap away. */}
      {spaces.length > 0 ? (
        <Reveal index={4}>
          <SectionLabel>Active spaces</SectionLabel>
          <View style={{ flexDirection: 'row', gap: spacing.smd, marginBottom: spacing.xxl - 2 }}>
            {spaces.slice(0, 2).map((space) => (
              <Card key={space.id} radius={radius.md} padding={0} style={{ flex: 1 }}>
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel={space.name}
                  onPress={() =>
                    router.push({ pathname: '/space/[id]', params: { id: space.id } })
                  }
                  haptic="selection"
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: spacing.smd,
                    padding: spacing.md,
                  }}
                >
                  <View
                    style={{
                      width: 36,
                      height: 36,
                      borderRadius: radius.sm,
                      backgroundColor: palette.surfaceVariant,
                      borderWidth: 1,
                      borderColor: palette.border,
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Glyph name="layers" size={16} />
                  </View>
                  <View style={{ flex: 1 }}>
                    <AppText variant="cardTitle" numberOfLines={1}>
                      {space.name}
                    </AppText>
                    <AppText variant="caption" tone="muted">
                      {space.memberCount} {space.memberCount === 1 ? 'member' : 'members'}
                    </AppText>
                  </View>
                </Touchable>
              </Card>
            ))}
          </View>
        </Reveal>
      ) : null}

      <Reveal index={5}>
        <SectionLabel>Recently captured</SectionLabel>
        <RecentlyCaptured />
      </Reveal>
    </Screen>
  );
}
