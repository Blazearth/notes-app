import { useRouter } from 'expo-router';
import React, { useMemo, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { STATUS_LABELS } from '@/saves/format';
import { useSaves } from '@/saves/SavesProvider';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';

const ALL = 'All';

/**
 * Plural display names for the knowledge types the registry ships today.
 *
 * Unknown types fall back to the raw name rather than being hidden: the server
 * registry is a data change by design, so a type this map has never heard of
 * must still appear in the Library.
 */
const TYPE_LABELS: Record<string, string> = {
  recipe: 'Recipes',
  movie: 'Watchlist',
  place: 'Places',
  other: 'Notes',
};

function labelFor(type: string): string {
  return TYPE_LABELS[type] ?? type.charAt(0).toUpperCase() + type.slice(1);
}

function HeaderAction({
  glyph,
  label,
  onPress,
}: {
  glyph: 'search' | 'filter';
  label: string;
  onPress?: () => void;
}) {
  const { palette, radius, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      weight="tile"
      onPress={onPress}
      // No haptic without a destination — a buzz would promise an action the
      // control does not perform.
      haptic={onPress ? 'selection' : null}
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

/** A real count of one knowledge type, replacing the sample "AI groups" grid. */
function TypeTile({ type, count }: { type: string; count: number }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <Card
      radius={radius.md}
      padding={spacing.md + 2}
      style={{ flexGrow: 1, flexBasis: '47%' }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
        <View
          style={{
            width: 8,
            height: 8,
            borderRadius: 4,
            backgroundColor: TYPE_COLORS[type] ?? TYPE_COLORS.other,
          }}
        />
        <AppText variant="cardTitle" style={{ fontSize: 13 }} numberOfLines={1}>
          {labelFor(type)}
        </AppText>
      </View>
      <AppText variant="caption" tone="muted" style={{ marginTop: spacing.xs }}>
        {count} {count === 1 ? 'save' : 'saves'}
      </AppText>
    </Card>
  );
}

/**
 * Everything the user has saved, grouped by what the pipeline decided it was.
 *
 * Reads the same `SavesProvider` the Home feed does rather than fetching again:
 * one list, one refresh, and a save created in Capture appears in both without
 * either screen knowing about the other.
 */
export function LibraryScreen() {
  const { palette, layout, spacing } = useTheme();
  const { saves, status, error, refresh, refreshing } = useSaves();
  const router = useRouter();
  const [filter, setFilter] = useState<string>(ALL);

  // Counts drive both the tiles and the filter chips, so a type with nothing in
  // it never appears as an empty option the user can select into a dead end.
  const counts = useMemo(() => {
    const tally = new Map<string, number>();
    for (const save of saves) {
      if (save.status !== 'ready' || !save.knowledgeType) continue;
      tally.set(save.knowledgeType, (tally.get(save.knowledgeType) ?? 0) + 1);
    }
    return [...tally.entries()].sort((a, b) => b[1] - a[1]);
  }, [saves]);

  const visible: SaveResponse[] = useMemo(
    () => (filter === ALL ? saves : saves.filter((s) => s.knowledgeType === filter)),
    [saves, filter],
  );

  const filters = [ALL, ...counts.map(([type]) => type)];

  return (
    <Screen
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
      <Reveal
        index={0}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.lg,
        }}
      >
        <AppText variant="display">Library</AppText>
        <View style={{ flexDirection: 'row', gap: spacing.sm }}>
          <HeaderAction glyph="search" label="Search saves" onPress={() => router.push('/search')} />
          {/* Sorting has no server support yet — deliberately inert rather than
              removed, so the affordance stays where it will land. */}
          <HeaderAction glyph="filter" label="Filter and sort" />
        </View>
      </Reveal>

      {status === 'loading' ? (
        <View style={{ paddingVertical: spacing.xxl * 2, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {status === 'error' ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              {error?.kind === 'network' ? "Can't reach Weavr" : 'Could not load your library'}
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
        </Reveal>
      ) : null}

      {status === 'ready' && saves.length === 0 ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Your library is empty
            </AppText>
            <AppText variant="caption" tone="muted">
              Tap + and paste a link, or share something into Weavr from another app.
            </AppText>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && saves.length > 0 ? (
        <>
          {filters.length > 1 ? (
            <Reveal index={1}>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                style={{ marginHorizontal: -layout.screenGutter, marginBottom: spacing.lg + 2 }}
                contentContainerStyle={{
                  paddingHorizontal: layout.screenGutter,
                  gap: spacing.sm,
                }}
              >
                {filters.map((option) => (
                  <Chip
                    key={option}
                    label={option === ALL ? ALL : labelFor(option)}
                    selected={option === filter}
                    onPress={() => setFilter(option)}
                  />
                ))}
              </ScrollView>
            </Reveal>
          ) : null}

          {counts.length > 0 && filter === ALL ? (
            <Reveal index={2}>
              <SectionLabel>By type</SectionLabel>
              <View
                style={{
                  flexDirection: 'row',
                  flexWrap: 'wrap',
                  gap: spacing.smd,
                  marginBottom: spacing.xxl - 2,
                }}
              >
                {counts.map(([type, count]) => (
                  <TypeTile key={type} type={type} count={count} />
                ))}
              </View>
            </Reveal>
          ) : null}

          <Reveal index={3}>
            <SectionLabel>{filter === ALL ? 'Everything' : labelFor(filter)}</SectionLabel>
          </Reveal>
          <View style={{ gap: spacing.smd }}>
            {visible.length > 0 ? (
              visible.map((save, i) => (
                // Keyed by filter as well as id, so switching filters remounts
                // the rows and they animate in. Without the filter in the key,
                // React reuses the survivors and a filter change lands silently.
                <Reveal key={`${filter}-${save.id}`} index={i}>
                  <SaveCard
                    save={save}
                    onPress={() =>
                      router.push({ pathname: '/save/[id]', params: { id: save.id } })
                    }
                    trailing={
                      save.status === 'ready' ? undefined : (
                        <AppText variant="caption" tone="muted" style={{ fontSize: 10 }}>
                          {STATUS_LABELS[save.status]}
                        </AppText>
                      )
                    }
                  />
                </Reveal>
              ))
            ) : (
              <Reveal key={`${filter}-empty`}>
                <Card>
                  <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
                    Nothing here yet
                  </AppText>
                  <AppText variant="caption" tone="muted">
                    Saves land in {labelFor(filter)} once the pipeline classifies them.
                  </AppText>
                </Card>
              </Reveal>
            )}
          </View>
        </>
      ) : null}
    </Screen>
  );
}
