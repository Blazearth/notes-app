import { useKeepAwake } from 'expo-keep-awake';
import { useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { ActivityIndicator, Alert, TextInput, View } from 'react-native';

import type { CollectionEntityResponse, CollectionNodeResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { RestTimer } from '@/components/RestTimer';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { entityMetaLine } from '@/collections/entityFields';
import { nodeType } from '@/collections/collectionMeta';
import { parseLeadingInt } from '@/collections/workoutLoad';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readCollectionEntities, readCollectionNode } from '@/local/derived';
import { useTheme } from '@/theme/ThemeProvider';

/** One logged set — reps and weight as free text, since a bodyweight set has no weight and a timed one has no reps. */
interface SetLog {
  reps: string;
  weight: string;
  done: boolean;
}

function defaultSets(entity: CollectionEntityResponse): SetLog[] {
  const count = parseLeadingInt(entity.fields.sets) ?? 1;
  return Array.from({ length: count }, () => ({ reps: '', weight: '', done: false }));
}

/**
 * Cues as separate lines rather than one run-on sentence — the same text
 * `entityDetailFields` already flattens to a single `·`-joined string for the
 * generic sheet, read back apart for a glanceable list. A source that gave a
 * single sentence still splits on the same separator, which degrades to one
 * line rather than nothing.
 */
function cuesList(fields: Record<string, unknown>): string[] {
  const raw = fields.cues;
  const parts = Array.isArray(raw)
    ? raw.filter((v): v is string => typeof v === 'string')
    : typeof raw === 'string'
      ? raw.split('·')
      : [];
  return parts.map((v) => v.trim()).filter((v) => v && v.toLowerCase() !== '[unclear]');
}

const EMPTY_ENTITIES: CollectionEntityResponse[] = [];

/**
 * Working through a collection's merged exercises — the "Start workout"
 * action on a training split.
 *
 * The distinction from `SaveDetailScreen`'s per-save exercise list is the
 * whole point of the collection layer: this runs the **union** of what every
 * one of your push days prescribes, deduplicated, rather than one creator's
 * routine. Bench press appears once even though two saves programme it, with
 * the first source's prescription — the same rollup rule the rest of the
 * merge uses.
 *
 * Redesigned 2026-08-27 around one hero exercise at a time (an external
 * design review's core ask: "glance → perform → log → rest → continue", not
 * a scrollable content library) rather than all seven cards open at once.
 * Purely a layout change — no new persisted state, same `completed`/
 * `setLogs`/`RestTimer` this screen already had.
 *
 * Three deliberate limits, all of the same kind — this screen *runs* the
 * library, it does not invent a programme:
 *
 * - Exercise order is the merge's order (first appearance across your saves),
 *   not a generated one. Ordering a session is programming advice, and this
 *   layer's rule is that a merged exercise is a fact about the library while
 *   an invented programme is not.
 * - Ticking an exercise writes the same `entity_states.done` the collection
 *   screen writes. There is no separate "session" record, so nothing has to
 *   be reconciled and a session abandoned halfway leaves exactly the state
 *   the user actually reached.
 * - The rest timer is not persisted — see `RestTimer`.
 * - Per-set reps/weight logs are session-local state too, same reasoning:
 *   what someone lifted today is not a fact about the split, and writing it
 *   anywhere durable would need a place to put it this layer doesn't have —
 *   there is no per-set history feature yet, only "did I do the movement."
 *
 * `useKeepAwake` holds the screen on for the lifetime of the component and
 * releases on unmount, which is precisely the session's lifetime — the same
 * reason cook mode uses it.
 */
export function WorkoutSessionScreen({ nodeId }: { nodeId: string }) {
  const { palette, radius, spacing, alpha } = useTheme();
  const router = useRouter();
  useKeepAwake();

  const type = nodeType(nodeId);

  const node = useLiveValue<CollectionNodeResponse | null>(
    DERIVED_TABLES,
    (store) => readCollectionNode(store, nodeId),
    null,
    [nodeId],
  );
  const entities = useLiveValue<CollectionEntityResponse[]>(
    DERIVED_TABLES,
    (store) => readCollectionEntities(store, nodeId),
    EMPTY_ENTITIES,
    [nodeId],
  );

  /**
   * Which exercises are ticked is *session-local*, not `entity_states`.
   *
   * Marking an exercise done in a collection means "this is a movement I have
   * dealt with"; doing it in a session means "I finished that set, this
   * time". Writing the session's ticks to the shared flag would leave every
   * exercise you have ever performed permanently greyed out in the
   * collection, which is the opposite of what a training split is for. The
   * session is a thing happening now — same reasoning as the rest timer.
   */
  const [completed, setCompleted] = useState<Set<string>>(new Set());

  /**
   * Set-by-set reps/weight, keyed by entity — undefined until the first log,
   * at which point it seeds from the prescribed set count (`entity.fields.sets`,
   * or 1 when the source didn't state one).
   */
  const [setLogs, setSetLogs] = useState<Record<string, SetLog[]>>({});
  const logsFor = useCallback(
    (entity: CollectionEntityResponse): SetLog[] => setLogs[entity.entityKey] ?? defaultSets(entity),
    [setLogs],
  );
  const updateSet = useCallback(
    (entity: CollectionEntityResponse, index: number, patch: Partial<SetLog>) => {
      setSetLogs((prev) => {
        const current = prev[entity.entityKey] ?? defaultSets(entity);
        return { ...prev, [entity.entityKey]: current.map((s, i) => (i === index ? { ...s, ...patch } : s)) };
      });
    },
    [],
  );
  const addSet = useCallback((entity: CollectionEntityResponse) => {
    setSetLogs((prev) => {
      const current = prev[entity.entityKey] ?? defaultSets(entity);
      return { ...prev, [entity.entityKey]: [...current, { reps: '', weight: '', done: false }] };
    });
  }, []);

  const total = entities.length;
  const doneCount = useMemo(
    () => entities.filter((e) => completed.has(e.entityKey)).length,
    [entities, completed],
  );
  const finished = total > 0 && doneCount === total;

  /**
   * The exercise on screen. `manualIndex` is set only by an explicit tap on
   * the "Up next" list (jump ahead, glance back at one already done) — it is
   * cleared the moment an exercise is completed, so the hero falls back to
   * "the next thing not yet done" rather than sitting on a finished exercise
   * after the button that finished it.
   */
  const [manualIndex, setManualIndex] = useState<number | null>(null);
  const currentIndex = useMemo(() => {
    if (manualIndex !== null && manualIndex >= 0 && manualIndex < entities.length) return manualIndex;
    const firstOpen = entities.findIndex((e) => !completed.has(e.entityKey));
    return firstOpen === -1 ? Math.max(0, entities.length - 1) : firstOpen;
  }, [manualIndex, entities, completed]);
  const current = entities[currentIndex] ?? null;
  const currentDone = current ? completed.has(current.entityKey) : false;

  const completeCurrent = useCallback(() => {
    if (!current) return;
    setCompleted((prev) => new Set(prev).add(current.entityKey));
    setManualIndex(null);
  }, [current]);
  const reopenCurrent = useCallback(() => {
    if (!current) return;
    setCompleted((prev) => {
      const next = new Set(prev);
      next.delete(current.entityKey);
      return next;
    });
  }, [current]);
  const goTo = useCallback((index: number) => setManualIndex(index), []);
  const goPrev = useCallback(() => setManualIndex(Math.max(0, currentIndex - 1)), [currentIndex]);
  const goNext = useCallback(
    () => setManualIndex(Math.min(total - 1, currentIndex + 1)),
    [currentIndex, total],
  );

  const confirmEnd = useCallback(() => {
    Alert.alert(
      'End workout?',
      "Nothing here is saved to your library — you'll lose this session's progress.",
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'End workout', style: 'destructive', onPress: () => router.back() },
      ],
    );
  }, [router]);

  /** Total sets actually logged, for the completion summary — not the prescribed count. */
  const loggedSets = useMemo(
    () => entities.reduce((sum, e) => sum + (setLogs[e.entityKey]?.filter((s) => s.done).length ?? 0), 0),
    [entities, setLogs],
  );

  if (!node && entities.length === 0) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  const rest = current && typeof current.fields.rest === 'string' ? current.fields.rest : null;
  const restUsable = !!rest && rest.trim() && rest.trim().toLowerCase() !== '[unclear]';
  const meta = current ? entityMetaLine(type, null, current.fields) : null;
  const cues = current ? cuesList(current.fields) : [];
  const sets = current ? logsFor(current) : [];

  return (
    <Screen>
      <Reveal index={0}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="End workout"
          onPress={confirmEnd}
          style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginBottom: spacing.md }}
        >
          <Glyph name="close" size={16} color={palette.textFaint} />
          <AppText variant="caption" tone="muted">
            End
          </AppText>
        </Touchable>

        <AppText variant="caption" tone="muted" style={{ marginBottom: 2 }}>
          {node?.name ?? 'Workout'}
        </AppText>
        <AppText variant="title" style={{ marginBottom: spacing.xs }}>
          {finished ? 'Session complete' : `Exercise ${currentIndex + 1} of ${total}`}
        </AppText>

        <View
          style={{
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.surfaceVariant,
            overflow: 'hidden',
            marginBottom: spacing.lg,
          }}
        >
          <View
            style={{
              width: `${total === 0 ? 0 : (doneCount / total) * 100}%`,
              height: '100%',
              backgroundColor: palette.accent,
            }}
          />
        </View>
      </Reveal>

      {current && !finished ? (
        <Reveal index={1}>
          <Card radius={radius.lg} style={{ marginBottom: spacing.lg }}>
            <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm }}>
              <View style={{ flex: 1 }}>
                <AppText
                  variant="heading"
                  tone={currentDone ? 'muted' : undefined}
                  style={currentDone ? { textDecorationLine: 'line-through' } : undefined}
                >
                  {current.name}
                </AppText>
                {meta ? (
                  <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
                    {meta}
                  </AppText>
                ) : null}
              </View>
              {currentDone ? <Glyph name="checkSquare" size={20} color={palette.accent} /> : null}
            </View>

            {restUsable ? <RestTimer label={rest.trim()} /> : null}

            {cues.length > 0 ? (
              <View style={{ marginTop: spacing.md }}>
                <SectionLabel>Form cues</SectionLabel>
                <View style={{ gap: spacing.xs }}>
                  {cues.map((cue, i) => (
                    <View key={i} style={{ flexDirection: 'row', gap: spacing.xs }}>
                      <AppText variant="bodySmall" tone="muted">
                        {'•'}
                      </AppText>
                      <AppText variant="bodySmall" style={{ flex: 1 }}>
                        {cue}
                      </AppText>
                    </View>
                  ))}
                </View>
              </View>
            ) : null}

            <View style={{ marginTop: spacing.md }}>
              <SectionLabel>Sets</SectionLabel>
              <View style={{ flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.xs }}>
                <AppText variant="caption" tone="muted" style={{ width: 44 }}>
                  SET
                </AppText>
                <AppText variant="caption" tone="muted" style={{ flex: 1 }}>
                  WEIGHT
                </AppText>
                <AppText variant="caption" tone="muted" style={{ flex: 1 }}>
                  REPS
                </AppText>
                <View style={{ width: 18 }} />
              </View>
              <View style={{ gap: spacing.xs }}>
                {sets.map((set, setIndex) => (
                  <View key={setIndex} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
                    <AppText variant="caption" tone="muted" style={{ width: 44 }}>
                      {setIndex + 1}
                    </AppText>
                    <TextInput
                      value={set.weight}
                      onChangeText={(v) => updateSet(current, setIndex, { weight: v })}
                      placeholder="—"
                      placeholderTextColor={palette.textFaint}
                      keyboardType="decimal-pad"
                      accessibilityLabel={`${current.name}, set ${setIndex + 1}, weight`}
                      style={{
                        flex: 1,
                        minWidth: 0,
                        paddingVertical: 6,
                        paddingHorizontal: spacing.sm,
                        borderRadius: radius.sm,
                        borderWidth: 1,
                        borderColor: palette.border,
                        backgroundColor: palette.surfaceVariant,
                        color: palette.text,
                        fontSize: 13,
                      }}
                    />
                    <TextInput
                      value={set.reps}
                      onChangeText={(v) => updateSet(current, setIndex, { reps: v })}
                      placeholder="—"
                      placeholderTextColor={palette.textFaint}
                      keyboardType="number-pad"
                      accessibilityLabel={`${current.name}, set ${setIndex + 1}, reps`}
                      style={{
                        flex: 1,
                        minWidth: 0,
                        paddingVertical: 6,
                        paddingHorizontal: spacing.sm,
                        borderRadius: radius.sm,
                        borderWidth: 1,
                        borderColor: palette.border,
                        backgroundColor: palette.surfaceVariant,
                        color: palette.text,
                        fontSize: 13,
                      }}
                    />
                    <Touchable
                      accessibilityRole="checkbox"
                      accessibilityState={{ checked: set.done }}
                      accessibilityLabel={`Set ${setIndex + 1} of ${current.name}: ${set.done ? 'done' : 'not done'}`}
                      onPress={() => updateSet(current, setIndex, { done: !set.done })}
                      haptic="light"
                    >
                      <Glyph
                        name={set.done ? 'checkSquare' : 'square'}
                        size={18}
                        color={set.done ? palette.accent : palette.textMuted}
                      />
                    </Touchable>
                  </View>
                ))}
              </View>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={`Add a set to ${current.name}`}
                onPress={() => addSet(current)}
                haptic="light"
                style={{
                  alignSelf: 'flex-start',
                  marginTop: spacing.sm,
                  paddingVertical: 6,
                  paddingHorizontal: spacing.smd,
                  borderRadius: 100,
                  borderWidth: 1,
                  borderColor: palette.border,
                }}
              >
                <AppText variant="caption" tone="accent">
                  + Add set
                </AppText>
              </Touchable>
            </View>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: spacing.lg }}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Previous exercise"
                disabled={currentIndex === 0}
                onPress={goPrev}
                haptic="light"
                baseOpacity={currentIndex === 0 ? alpha.disabled : 1}
                style={{ padding: spacing.xs }}
              >
                <Glyph name="chevron" size={16} color={palette.textMuted} />
              </Touchable>

              {currentDone ? (
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel="Mark exercise not done"
                  onPress={reopenCurrent}
                  haptic="light"
                  weight="card"
                  style={{
                    flex: 1,
                    alignItems: 'center',
                    paddingVertical: spacing.smd,
                    borderRadius: 100,
                    borderWidth: 1,
                    borderColor: palette.border,
                  }}
                >
                  <AppText variant="label" tone="muted">
                    Mark not done
                  </AppText>
                </Touchable>
              ) : (
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel="Complete exercise"
                  onPress={completeCurrent}
                  haptic="medium"
                  weight="card"
                  style={{
                    flex: 1,
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: spacing.xs,
                    paddingVertical: spacing.smd,
                    borderRadius: 100,
                    backgroundColor: palette.accent,
                  }}
                >
                  <AppText variant="label" style={{ color: palette.background }}>
                    Complete exercise
                  </AppText>
                  <View style={{ transform: [{ scaleX: -1 }] }}>
                    <Glyph name="chevron" size={14} color={palette.background} />
                  </View>
                </Touchable>
              )}

              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Next exercise"
                disabled={currentIndex === total - 1}
                onPress={goNext}
                haptic="light"
                baseOpacity={currentIndex === total - 1 ? alpha.disabled : 1}
                style={{ padding: spacing.xs }}
              >
                <View style={{ transform: [{ scaleX: -1 }] }}>
                  <Glyph name="chevron" size={16} color={palette.textMuted} />
                </View>
              </Touchable>
            </View>
          </Card>
        </Reveal>
      ) : null}

      {finished ? (
        <Reveal index={1}>
          <Card style={{ marginBottom: spacing.lg }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.xs }}>
              <Glyph name="checkSquare" size={20} color={palette.accent} />
              <AppText variant="cardTitle">Every exercise done</AppText>
            </View>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              {`${total} exercise${total === 1 ? '' : 's'}${loggedSets > 0 ? ` · ${loggedSets} set${loggedSets === 1 ? '' : 's'} logged` : ''}. Nothing was saved — a session is a thing you did, not a fact about your library.`}
            </AppText>
            <Touchable accessibilityRole="button" accessibilityLabel="Finish session" onPress={() => router.back()} haptic="medium">
              <AppText variant="label" tone="accent">
                Finish
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}

      <Reveal index={2}>
        <SectionLabel>All exercises</SectionLabel>
      </Reveal>
      <View style={{ gap: spacing.xs }}>
        {entities.map((entity, i) => {
          const done = completed.has(entity.entityKey);
          const isCurrent = !finished && i === currentIndex;
          return (
            <Reveal key={entity.entityKey} index={i + 3}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={`${entity.name}: ${done ? 'done' : 'not done'}${isCurrent ? ', current exercise' : ''}. Tap to view.`}
                onPress={() => goTo(i)}
                haptic="selection"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: spacing.smd,
                  paddingVertical: spacing.sm,
                  paddingHorizontal: spacing.smd,
                  borderRadius: radius.md,
                  backgroundColor: isCurrent ? palette.surfaceVariant : 'transparent',
                }}
              >
                <AppText variant="caption" tone="muted" style={{ width: 20 }}>
                  {String(i + 1).padStart(2, '0')}
                </AppText>
                <AppText
                  variant="bodySmall"
                  tone={done ? 'muted' : undefined}
                  style={[{ flex: 1 }, done ? { textDecorationLine: 'line-through' } : null]}
                  numberOfLines={1}
                >
                  {entity.name}
                </AppText>
                <Glyph
                  name={done ? 'checkSquare' : 'square'}
                  size={16}
                  color={done ? palette.accent : palette.textFaint}
                />
              </Touchable>
            </Reveal>
          );
        })}
      </View>
    </Screen>
  );
}
