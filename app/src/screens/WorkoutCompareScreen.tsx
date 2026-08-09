import { useRouter } from 'expo-router';
import React from 'react';
import { ActivityIndicator, ScrollView, View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useLive } from '@/local';
import { compareWorkouts, type WorkoutCompareRow } from '@/saves/workoutCompare';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for the "store has nothing yet" case — see `useLive`. */
const EMPTY_SAVES: SaveResponse[] = [];

function Chip({ label, muted }: { label: string; muted?: boolean }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <View
      style={{
        paddingVertical: 3,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.pill,
        backgroundColor: muted ? palette.surfaceVariant : `${palette.accent}1a`,
        borderWidth: 1,
        borderColor: muted ? palette.border : `${palette.accent}40`,
      }}
    >
      <AppText variant="caption" tone={muted ? 'muted' : 'accent'} style={{ fontSize: 11 }}>
        {label}
      </AppText>
    </View>
  );
}

function StatLine({ label, value }: { label: string; value: string }) {
  const { spacing } = useTheme();
  return (
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginBottom: spacing.xs }}>
      <AppText variant="caption" tone="muted">
        {label}
      </AppText>
      <AppText variant="caption" style={{ maxWidth: '60%', textAlign: 'right' }}>
        {value}
      </AppText>
    </View>
  );
}

/** One workout's column in the side-by-side compare. */
function CompareColumn({ row }: { row: WorkoutCompareRow }) {
  const { spacing } = useTheme();
  return (
    <Card radius={16} style={{ width: 220, marginRight: spacing.smd }}>
      <AppText variant="cardTitle" numberOfLines={2} style={{ marginBottom: spacing.sm, minHeight: 40 }}>
        {row.title}
      </AppText>

      <StatLine label="Goal" value={row.goal ?? '—'} />
      <StatLine
        label="Duration"
        value={row.durationMin ? `${row.durationMin} min${row.durationIsEstimate ? ' (est.)' : ''}` : '—'}
      />
      <StatLine
        label="Difficulty"
        value={row.difficulty ? `${row.difficulty}${row.difficultyIsEstimate ? ' (est.)' : ''}` : '—'}
      />
      <StatLine label="Exercises" value={String(row.exerciseCount)} />

      {row.muscleGroups.length > 0 ? (
        <View style={{ marginTop: spacing.sm }}>
          <AppText variant="caption" tone="faint" style={{ marginBottom: spacing.xs }}>
            Muscle groups
          </AppText>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}>
            {row.muscleGroups.map((m) => (
              <Chip key={m} label={m} muted />
            ))}
          </View>
        </View>
      ) : null}
    </Card>
  );
}

/**
 * K5's shipped alternative to AI synthesis — side-by-side comparison of
 * several workout saves, entirely local compute (`compareWorkouts`). No
 * merge, no rewrite: each source keeps its own column, and the only thing
 * computed across them is a plain set intersection for "in common".
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
          {model.rows.length} of {ids.length} selected — side by side, nothing merged
        </AppText>
      </Reveal>

      {model.rows.length < 2 ? (
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
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginBottom: spacing.xxl - 2 }}>
              {model.rows.map((row) => (
                <CompareColumn key={row.saveId} row={row} />
              ))}
            </ScrollView>
          </Reveal>

          {model.commonMuscleGroups.length > 0 || model.commonEquipment.length > 0 ? (
            <Reveal index={2}>
              <SectionLabel>In common</SectionLabel>
              <Card style={{ marginBottom: spacing.md }}>
                {model.commonMuscleGroups.length > 0 ? (
                  <View style={{ marginBottom: model.commonEquipment.length > 0 ? spacing.sm : 0 }}>
                    <AppText variant="caption" tone="faint" style={{ marginBottom: spacing.xs }}>
                      Muscle groups every workout trains
                    </AppText>
                    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}>
                      {model.commonMuscleGroups.map((m) => (
                        <Chip key={m} label={m} />
                      ))}
                    </View>
                  </View>
                ) : null}
                {model.commonEquipment.length > 0 ? (
                  <View>
                    <AppText variant="caption" tone="faint" style={{ marginBottom: spacing.xs }}>
                      Equipment every workout needs
                    </AppText>
                    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}>
                      {model.commonEquipment.map((e) => (
                        <Chip key={e} label={e} />
                      ))}
                    </View>
                  </View>
                ) : null}
              </Card>
            </Reveal>
          ) : null}
        </>
      )}
    </Screen>
  );
}
