import { useRouter } from 'expo-router';
import React, { useRef } from 'react';
import { ActivityIndicator, Alert, RefreshControl, ScrollView, View } from 'react-native';

import { MOCK_CATEGORIES, MOCK_SOURCE_LABELS, type KnowledgeGroup } from '@/data';
import type { DigestResponse, SaveResponse, Space } from '@/api/types';
import type { NextAction } from '@/collections/nextAction';
import { KV, useLiveValue } from '@/local';
import { DERIVED_TABLES, readContinueSaves, readGroups, readTopNextAction } from '@/local/derived';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { NextActionCard } from '@/components/NextActionCard';

import { SaveThumb } from '@/components/SaveThumb';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useSession } from '@/auth/SessionProvider';
import { USE_MOCK_DATA } from '@/data/config';
import { GREETING_NAME } from '@/data/sampleContent';
import { morphFrom } from '@/motion/morph';
import { usePreferences } from '@/prefs/PreferencesProvider';
import { useDigestDismissed } from '@/saves/digestDismiss';
import { daysLeftInWeek } from '@/saves/digestWeek';
import { STATUS_LABELS, saveTitle } from '@/saves/format';
import { useSaves } from '@/saves/SavesProvider';
import { writeSaveFlags, writeDeleteSave } from '@/local/writes';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * `GREETING_NAME` ('Maya') is mock-mode demo content and must never leak into
 * a real session as a fallback — that read as the app mistaking a stranger
 * for a fixture. A real user with no `prefs.userName` set yet gets their
 * email's local part instead of a fake name or a generic "You".
 */
function fallbackName(email: string | null | undefined): string {
  if (USE_MOCK_DATA) return GREETING_NAME;
  const local = email?.split('@')[0];
  return local ? local.charAt(0).toUpperCase() + local.slice(1) : 'there';
}

/**
 * How many saves Home shows before deferring to search.
 *
 * Five, because Home is a summary and the sections under the feed have to stay
 * reachable. An uncapped feed pushes everything below it off the screen and
 * quietly turns the home screen into the library.
 */
const RECENT_LIMIT = 5;

// Stable identities for the "store has nothing yet" case. A fresh `[]` each
// render would re-run every memo below it for no change.
const EMPTY_SAVES: SaveResponse[] = [];
const EMPTY_SPACES: Space[] = [];
const EMPTY_GROUPS: KnowledgeGroup[] = [];

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

/**
 * A course's real per-section completion, when there is any — finer-grained
 * than the coarse four-step `lifecycleStatus` bar every other type falls
 * back to. `docs/next-phases.md` §4.2: "feeds the existing Continue rail
 * with real data." Falls back to the ordinary lifecycle bar until at least
 * one section has been ticked, so a course just saved reads as "Planned"
 * rather than "0/6 sections" — the same story every other type tells.
 */
function progressForSave(save: SaveResponse): { fraction: number; label: string } {
  if (save.knowledgeType === 'course' && Array.isArray(save.structuredData?.sections)) {
    const total = save.structuredData.sections.length;
    const done = save.structuredData.sections.filter(
      (_, i) => save.itemStates?.[`sections[${i}]`]?.done === true,
    ).length;
    if (total > 0 && done > 0) {
      return {
        fraction: done / total,
        label: done === total ? 'Done' : `${done}/${total} sections`,
      };
    }
  }
  return LIFECYCLE_PROGRESS[save.lifecycleStatus ?? 'saved'] ?? { fraction: 0, label: 'Saved' };
}

function ContinueCard({ save, onPress }: { save: SaveResponse; onPress: () => void }) {
  const { palette, radius, spacing } = useTheme();
  const progress = progressForSave(save);
  const meta = save.knowledgeType ? saveTypeMeta(save.knowledgeType) : undefined;

  return (
    <Card padding={0} radius={radius.lg} style={{ width: 156, overflow: 'hidden' }}>
      <Touchable accessibilityRole="button" onPress={onPress} haptic="selection">
        <SaveThumb
          thumbnailUrl={save.thumbnailUrl}
          width={156}
          height={90}
          radius={0}
          tint={meta?.color}
          glyph={meta?.glyph}
        />
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
 * One AI-derived collection.
 *
 * `flexBasis: '48%'` with `flexWrap` rather than two hard-coded columns: the
 * cards reflow on a wider screen without a breakpoint, and — unlike `flex: 1`
 * inside a wrapping row — a percentage basis is a real number Yoga can measure,
 * so the card keeps its content height. That distinction cost a whole debugging
 * session on the Capture sheet; it is worth not repeating here.
 */
const GroupCard = React.memo(function GroupCard({
  group,
  onPress,
}: {
  group: KnowledgeGroup;
  onPress: () => void;
}) {
  const { spacing, radius } = useTheme();
  const preview = subgroupPreview(group);

  return (
    <Card padding={0} radius={radius.md} style={{ flexBasis: '48%', flexGrow: 1, overflow: 'hidden' }}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${group.name}, ${group.itemCount} items`}
        onPress={onPress}
        haptic="selection"
        style={{ padding: spacing.md, gap: spacing.xs }}
      >
        {/* No emoji, and no icon standing in for one — the visual design system
            will bring illustrations, and a placeholder glyph now would be a
            thing to remove rather than a thing to replace. `illustration` and
            `coverImage` are on the model waiting for it. */}
        <AppText variant="cardTitle" numberOfLines={1}>
          {group.name}
        </AppText>
        {/* The subgroups, not adjectives about the group. "Movies • TV Series •
            Anime • +2" tells you how the thing is organised and what tapping it
            will show; "Films • Series • Weekend" told you nothing you could
            navigate by. */}
        {preview ? (
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {preview}
          </AppText>
        ) : null}
        <AppText variant="caption" tone="faint">
          {group.itemCount} items
        </AppText>
      </Touchable>
    </Card>
  );
});

/**
 * "Movies • TV Series • Anime • +2".
 *
 * Three names then a remainder. Four fits on a wide screen and truncates
 * mid-word on a narrow one, and a clipped subgroup name reads as a rendering
 * fault where an explicit `+2` reads as a fact.
 */
const PREVIEW_LIMIT = 3;

function subgroupPreview(group: KnowledgeGroup): string | null {
  if (group.subgroups.length === 0) return null;
  const shown = group.subgroups.slice(0, PREVIEW_LIMIT).map((s) => s.name);
  const rest = group.subgroups.length - shown.length;
  return [...shown, ...(rest > 0 ? [`+${rest}`] : [])].join(' • ');
}

/**
 * The live feed section.
 *
 * All four states are real here, because with no job runner yet the interesting
 * ones are the common ones: a save is created and then sits at `processing`
 * indefinitely, and an unreachable API is the single most likely thing to happen
 * during development.
 */
function RecentlyCaptured({ limit }: { limit: number }) {
  const { palette, spacing } = useTheme();
  const { saves, status, error, refresh } = useSaves();
  // Don't show archived saves on Home — they belong in the Archived filter in Library
  const visibleSaves = saves.filter((s) => !s.archived);
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

  // Home shows a fixed number and stops. The full list is what the Library is
  // for, and a home screen that grows without bound stops being a summary —
  // every section below it becomes unreachable without a long scroll.
  const shown = visibleSaves.slice(0, limit);

  const confirmDelete = (saveId: string) => {
    Alert.alert(
      'Delete save',
      'This will permanently delete this save. It cannot be undone.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => writeDeleteSave(saveId) },
      ],
    );
  };

  return (
    <View style={{ gap: spacing.smd }}>
      {shown.map((save) => (
        <SaveCard
          key={save.id}
          save={save}
          onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
          subtitleOverride={sourceLine(save)}
          onFavorite={() => writeSaveFlags(save.id, { favorite: !save.favorite })}
          onArchive={() => writeSaveFlags(save.id, { archived: true })}
          onDelete={() => confirmDelete(save.id)}
          trailing={
            save.status === 'ready' ? undefined : (
              <StatusPill label={STATUS_LABELS[save.status]} tint={tintFor(save.status)} />
            )
          }
        />
      ))}
      {visibleSaves.length > shown.length ? (
        <Touchable
          accessibilityRole="button"
          onPress={() => router.push('/search')}
          haptic="selection"
          style={{ alignSelf: 'center', paddingVertical: spacing.sm }}
        >
          <AppText variant="label" tone="accent" style={{ fontSize: 13 }}>
            See all {visibleSaves.length}
          </AppText>
        </Touchable>
      ) : null}
    </View>
  );
}

/**
 * "YouTube • Workout".
 *
 * Source and category are presentation-only and no endpoint serves either, so
 * they come from the mock layer keyed by save id and fall back to what the save
 * itself knows. Falling back rather than hiding matters: with the real backend
 * selected this line still renders, just from `sourceUrl` and `knowledgeType`.
 */
function sourceLine(save: SaveResponse): string | undefined {
  const source =
    MOCK_SOURCE_LABELS[save.id] ??
    (save.sourceUrl
      ? (() => {
          try {
            return new URL(save.sourceUrl).hostname.replace(/^www\./, '');
          } catch {
            return undefined;
          }
        })()
      : undefined);
  const category = MOCK_CATEGORIES[save.id] ?? save.knowledgeType;

  const parts = [source, category].filter(Boolean);
  return parts.length ? parts.join(' • ') : undefined;
}

export function HomeScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const { prefs } = usePreferences();
  const { session } = useSession();
  const { refresh, refreshing } = useSaves();
  const router = useRouter();

  const name = prefs.userName.trim() || fallbackName(session?.user.email);
  const greeting = greetingForHour(new Date().getHours());

  // Settings does not push — it grows out of this tile and collapses back into
  // it, so the tile's position on screen is what the transition is anchored to.
  // Measured at press time: this header scrolls, so its window coordinates are
  // only correct at the moment of the tap.
  const settingsAnchor = useRef<View | null>(null);

  // All four sections read the local store, and all four hide themselves when
  // there is nothing in it — so the first frame of a warm start has the rail,
  // the Spaces strip and the groups grid already on it, and a sync failure
  // degrades to "slightly stale" rather than to an error card above the feed.
  //
  // Two of them used to be their own requests and are now derived (see
  // `@/local/derived`): the Continue rail was `GET /v1/saves/lifecycle`
  // because the feed was paged and filtering page 0 would miss anything older,
  // and the groups grid was `GET /v1/groups`. With the whole library local,
  // neither is a request any more.
  const continueSaves = useLiveValue<SaveResponse[]>(['saves'], readContinueSaves, EMPTY_SAVES);
  const spaces = useLiveValue<Space[]>(['spaces'], (store) => store.readSpaces(), EMPTY_SPACES);
  const groups = useLiveValue<KnowledgeGroup[]>(DERIVED_TABLES, readGroups, EMPTY_GROUPS);
  // The single highest-weighted `@/collections/nextAction` candidate across
  // every collection type's whole tree — Home asks the same engine a leaf
  // collection screen asks of its own node, just over the whole library
  // instead of one folder. Null is the honest common case: most libraries
  // clear no type's threshold most of the time, and "Today" simply doesn't
  // render rather than inventing something to fill it.
  const nextAction = useLiveValue<NextAction | null>(DERIVED_TABLES, readTopNextAction, null);
  // `pending` is never stored (see `sync.syncDigest`), so anything here is a
  // finished digest; `null` renders as absent rather than as a loading state
  // nobody would wait around for.
  const digest = useLiveValue<DigestResponse | null>(['kv'], (store) => store.readKv<DigestResponse>(KV.digest), null);
  // Dismissal is keyed to the digest's own `weekStart`, so a new week's digest
  // is never suppressed by last week's dismissal — see `digestDismiss.ts`.
  const { dismissed: digestDismissed, dismiss: dismissDigest, hydrated: digestDismissHydrated } =
    useDigestDismissed(digest?.weekStart);
  const digestDaysLeft = digest ? daysLeftInWeek(digest.weekStart) : 0;

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
        {/* Settings is the only action here.
            The shopping-list shortcut that used to sit beside it is reachable
            from the recipe it belongs to, and a header with one control says
            what the screen is for far better than a row of them. */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
          {/* `collapsable={false}` is required, not defensive: Android flattens
              a view that draws nothing out of the native hierarchy, and a
              flattened view has no window position to measure. */}
          <View ref={settingsAnchor} collapsable={false}>
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Settings"
              onPress={() =>
                morphFrom(settingsAnchor, radius.sm, () => router.push('/settings'))
              }
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

      {/* The one cross-type nudge for the day, asked of the same engine a
          leaf collection screen asks of its own node — see
          `@/collections/nextAction`. Absent far more often than present:
          most libraries clear no type's threshold most of the time, and an
          empty "Today" is the honest answer rather than a banner filled with
          something to say. */}
      {nextAction ? (
        <Reveal index={2}>
          <NextActionCard
            action={nextAction}
            label="Today"
            onPrimary={() => {
              if (nextAction.type === 'workout') {
                router.push({ pathname: '/session/[nodeId]', params: { nodeId: nextAction.nodeId } });
              } else {
                router.push({ pathname: '/collection/[type]', params: { type: nextAction.nodeId } });
              }
            }}
          />
        </Reveal>
      ) : null}

      {/* Real now, and absent when there is nothing in flight. The rail used
          to render three invented cards unconditionally; an empty rail is
          honest, three fake ones are not. */}
      {continueSaves.length > 0 ? (
        <Reveal index={3}>
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

      {/* The library, one level up: what the user has, rather than what they
          most recently added. Hidden when nothing can group — which is the
          honest rendering under the real backend, where no endpoint serves
          these yet. */}
      {groups.length > 0 ? (
        <Reveal index={4}>
          <SectionLabel>AI groups</SectionLabel>
          <View
            style={{
              flexDirection: 'row',
              flexWrap: 'wrap',
              gap: spacing.smd,
              marginBottom: spacing.xxl - 2,
            }}
          >
            {groups.map((group) => (
              <GroupCard
                key={group.id}
                group={group}
                onPress={() => router.push({ pathname: '/group/[id]', params: { id: group.id } })}
              />
            ))}
          </View>
        </Reveal>
      ) : null}

      {/* Hidden until a real digest exists — 'pending' and 'empty' both
          render as absent rather than as a loading or error state, since
          nobody is waiting on this tile the way they wait on the feed.
          Also hidden once dismissed for its own week — gated on
          `digestDismissHydrated` so a digest the user already dismissed
          never flashes on screen for a frame before disappearing. */}
      {digest?.status === 'ready' && digestDismissHydrated && !digestDismissed ? (
        <Reveal index={5}>
          <Card variant="accent" padding={spacing.lg} style={{ marginBottom: spacing.xxl - 2 }}>
            {/* On the accent container, not the page — so the label uses the
                container's computed on-colour rather than the accent itself.
                Days-left and dismiss share this header row rather than
                adding one of their own, so the card gains no extra height
                for them — both stay small and secondary next to the title. */}
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: spacing.sm,
              }}
            >
              <AppText variant="sectionLabel" tone="onAccentContainer">
                Weekly digest
              </AppText>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
                <AppText variant="caption" tone="onAccentContainer" style={{ fontSize: 11, opacity: 0.65 }}>
                  {digestDaysLeft} {digestDaysLeft === 1 ? 'day' : 'days'} left
                </AppText>
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel="Dismiss weekly digest for this week"
                  onPress={dismissDigest}
                  haptic="light"
                  weight="tile"
                  hitSlop={8}
                  style={{ width: 16, height: 16, alignItems: 'center', justifyContent: 'center' }}
                >
                  <Glyph name="close" size={11} weight={2} color={palette.onAccentContainer} style={{ opacity: 0.55 }} />
                </Touchable>
              </View>
            </View>
            <AppText tone="onAccentContainer">{digest.summary}</AppText>
          </Card>
        </Reveal>
      ) : null}

      {/* Real Spaces now, and hidden when the user is in none — the two
          invented ones that used to sit here were the last fiction on this
          screen. Capped at two: this is a glance, and the Spaces tab is one
          tap away. */}
      {spaces.length > 0 ? (
        <Reveal index={6}>
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

      <Reveal index={7}>
        <SectionLabel>Recently added</SectionLabel>
        <RecentlyCaptured limit={RECENT_LIMIT} />
      </Reveal>
    </Screen>
  );
}
