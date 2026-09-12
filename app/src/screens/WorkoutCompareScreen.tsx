import { useRouter } from 'expo-router';
import React, { useEffect, useRef } from 'react';
import { ActivityIndicator, View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useLive } from '@/local';
import {
  compareWorkouts,
  type WorkoutCompareColumn,
  type WorkoutCompareMembershipRow,
  type WorkoutCompareStatRow,
} from '@/saves/workoutCompare';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for the "store has nothing yet" case — see `useLive`. */
const EMPTY_SAVES: SaveResponse[] = [];

/** Fixed width for the attribute-label column so every row's value columns line up. */
const LABEL_WIDTH = 96;

function Header({ columns }: { columns: WorkoutCompareColumn[] }) {
  const { spacing } = useTheme();
  return (
    <View style={{ flexDirection: 'row', marginBottom: spacing.sm }}>
      <View style={{ width: LABEL_WIDTH }} />
      {columns.map((col) => (
        <View key={col.saveId} style={{ flex: 1, paddingHorizontal: 4 }}>
          <AppText variant="label" numberOfLines={2} style={{ textAlign: 'center' }}>
            {col.title}
          </AppText>
        </View>
      ))}
    </View>
  );
}

/**
 * One attribute row — a fixed label, then one value per workout. A row where
 * every workout agrees is de-emphasized (muted text, no fill); a row where
 * they differ is emphasized (default text weight, a faint accent fill) —
 * that contrast is the entire point of a comparison table.
 */
function StatRowView({ row }: { row: WorkoutCompareStatRow }) {
  const { palette, spacing, radius } = useTheme();
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: spacing.sm,
        paddingHorizontal: row.allSame ? 0 : spacing.xs,
        marginHorizontal: row.allSame ? 0 : -spacing.xs,
        borderRadius: radius.sm,
        backgroundColor: row.allSame ? 'transparent' : `${palette.accent}12`,
      }}
    >
      <View style={{ width: LABEL_WIDTH - (row.allSame ? 0 : spacing.xs) }}>
        <AppText variant="caption" tone={row.allSame ? 'faint' : 'muted'}>
          {row.label}
        </AppText>
      </View>
      {row.values.map((value, i) => (
        // eslint-disable-next-line react/no-array-index-key -- values are positional, same length as columns every render
        <View key={i} style={{ flex: 1, paddingHorizontal: 4 }}>
          <AppText
            variant="caption"
            tone={row.allSame ? 'muted' : 'default'}
            numberOfLines={1}
            style={{ textAlign: 'center' }}
          >
            {value.text}
            {value.estimate ? ' (est.)' : ''}
          </AppText>
        </View>
      ))}
    </View>
  );
}

/** A muscle-group / equipment row — a check where a workout has it, a dash where it doesn't. */
function MembershipRowView({ row }: { row: WorkoutCompareMembershipRow }) {
  const { palette, spacing } = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: spacing.xs }}>
      <View style={{ width: LABEL_WIDTH }}>
        <AppText variant="caption" tone={row.inAll ? 'default' : 'muted'} numberOfLines={2}>
          {row.label}
        </AppText>
      </View>
      {row.present.map((present, i) => (
        // eslint-disable-next-line react/no-array-index-key -- values are positional, same length as columns every render
        <View key={i} style={{ flex: 1, alignItems: 'center' }}>
          {present ? (
            <Glyph name="check" size={13} weight={2.5} color={palette.success} />
          ) : (
            <AppText variant="caption" tone="faint">
              —
            </AppText>
          )}
        </View>
      ))}
    </View>
  );
}

function SourceRow({ column, onPress }: { column: WorkoutCompareColumn; onPress: () => void }) {
  const { palette, spacing, radius, icon } = useTheme();
  return (
    <Card padding={0} radius={radius.md} style={{ marginBottom: spacing.sm }}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`Open ${column.title}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={2}>
            {column.title}
          </AppText>
          <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
            Saved from · {column.sourceLabel}
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
 * K5's shipped alternative to AI synthesis — a comparison table over
 * several workout saves, entirely local compute (`compareWorkouts`). No
 * merge, no rewrite: each source keeps its own column, and the only thing
 * computed across them is which attributes agree and a plain set
 * intersection for muscle groups / equipment "in common".
 *
 * A table rather than a row of cards: on a phone-width viewport a card per
 * workout only ever shows one card at a time, which defeats "compare" —
 * a table's rows stay legible because only the value columns need width,
 * and 2-3 short values fit without horizontal scrolling.
 *
 * Deliberately no "start this workout" / "save as routine" action spanning
 * the compared saves — that would mean synthesizing one routine out of
 * several sources' exercises, exactly the generative merge K5 declined (see
 * `workoutCompare.ts`). The action this screen offers instead is opening a
 * specific source, where Phase 4's own exercise/rest-timer controls already
 * live.
 */
export function WorkoutCompareScreen({ ids }: { ids: string[] }) {
  const { palette, spacing } = useTheme();
  const router = useRouter();

  // Straight from the store. This screen is only ever reached by selecting
  // saves that were just listed from the store one screen back, so N fetches
  // to re-read what is already local would be pure latency — and would break
  // the comparison entirely offline.
  const { data, loading } = useLive<SaveResponse[]>(
    ['saves'],
    (store) => store.readSavesByIds(ids),
    [ids.join(',')],
  );
  const saves = data ?? EMPTY_SAVES;
  const loaded = !loading;
  const error = loaded && saves.length < ids.length ? 'Could not load these workouts' : null;

  useEffect(() => {
    track(AnalyticsEvent.ActStarted, { act_type: 'compare_workouts' });
  }, []);

  // Viewing the comparison *is* completion — there is no further "finish"
  // action on this screen (§E's note on `act_completed`). Fires once, only
  // on the success path.
  const completedFired = useRef(false);
  useEffect(() => {
    if (!loaded || error || completedFired.current) return;
    completedFired.current = true;
    track(AnalyticsEvent.ActCompleted, { act_type: 'compare_workouts' });
  }, [loaded, error]);

  if (error) {
    return (
      <Screen>
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Compare unavailable
          </AppText>
          <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
            {error}
          </AppText>
          <Touchable accessibilityRole="button" onPress={() => router.back()} haptic="medium">
            <AppText variant="label" tone="accent">
              Go back
            </AppText>
          </Touchable>
        </Card>
      </Screen>
    );
  }

  if (!loaded) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  const model = compareWorkouts(saves);

  return (
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

        <AppText variant="title" style={{ marginBottom: spacing.xs }}>
          Compare workouts
        </AppText>
        <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xxl - 2 }}>
          {model.columns.length >= 2
            ? `Comparing ${model.columns.length} workouts`
            : `${model.columns.length} of ${ids.length} selected`}
        </AppText>
      </Reveal>

      {model.columns.length < 2 ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="caption" tone="muted">
              Select at least two workouts to compare.
            </AppText>
          </Card>
        </Reveal>
      ) : (
        <>
          <Reveal index={1}>
            <Card style={{ marginBottom: spacing.xxl - 2 }}>
              <Header columns={model.columns} />
              <View style={{ height: 1, backgroundColor: palette.border, marginBottom: spacing.xs }} />
              {model.stats.map((row) => (
                <StatRowView key={row.label} row={row} />
              ))}

              {model.muscleGroups.length > 0 ? (
                <>
                  <View style={{ height: 1, backgroundColor: palette.border, marginVertical: spacing.sm }} />
                  <AppText variant="caption" tone="faint" style={{ marginBottom: spacing.xs, letterSpacing: 0.5 }}>
                    MUSCLE GROUPS
                  </AppText>
                  {model.muscleGroups.map((row) => (
                    <MembershipRowView key={row.label} row={row} />
                  ))}
                </>
              ) : null}

              {model.equipment.length > 0 ? (
                <>
                  <View style={{ height: 1, backgroundColor: palette.border, marginVertical: spacing.sm }} />
                  <AppText variant="caption" tone="faint" style={{ marginBottom: spacing.xs, letterSpacing: 0.5 }}>
                    EQUIPMENT
                  </AppText>
                  {model.equipment.map((row) => (
                    <MembershipRowView key={row.label} row={row} />
                  ))}
                </>
              ) : null}
            </Card>
          </Reveal>

          <Reveal index={2}>
            <SectionLabel>Sources</SectionLabel>
            {model.columns.map((column) => (
              <SourceRow
                key={column.saveId}
                column={column}
                onPress={() => router.push({ pathname: '/save/[id]', params: { id: column.saveId } })}
              />
            ))}
          </Reveal>
        </>
      )}
    </Screen>
  );
}
