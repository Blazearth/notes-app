import { useKeepAwake } from 'expo-keep-awake';
import { useRouter } from 'expo-router';
import React, { useCallback, useMemo, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import type { CollectionEntityResponse, CollectionNodeResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { RestTimer } from '@/components/RestTimer';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { entityDetailFields, entityMetaLine } from '@/collections/entityFields';
import { nodeType } from '@/collections/collectionMeta';
import { useLiveValue } from '@/local';
import { DERIVED_TABLES, readCollectionEntities, readCollectionNode } from '@/local/derived';
import { useTheme } from '@/theme/ThemeProvider';

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
 *
 * `useKeepAwake` holds the screen on for the lifetime of the component and
 * releases on unmount, which is precisely the session's lifetime — the same
 * reason cook mode uses it.
 */
export function WorkoutSessionScreen({ nodeId }: { nodeId: string }) {
  const { palette, radius, spacing } = useTheme();
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
  const toggle = useCallback((key: string) => {
    setCompleted((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const total = entities.length;
  const doneCount = useMemo(
    () => entities.filter((e) => completed.has(e.entityKey)).length,
    [entities, completed],
  );
  const finished = total > 0 && doneCount === total;

  if (!node && entities.length === 0) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  return (
    <Screen>
      <Reveal index={0}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="End session"
          onPress={() => router.back()}
          style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginBottom: spacing.md }}
        >
          <Glyph name="close" size={16} />
          <AppText variant="caption" tone="muted">
            End session
          </AppText>
        </Touchable>

        <AppText variant="caption" tone="muted" style={{ marginBottom: 2 }}>
          {node?.name ?? 'Workout'}
        </AppText>
        <AppText variant="title" style={{ marginBottom: spacing.xs }}>
          {finished ? 'Session complete' : 'Session'}
        </AppText>
        <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
          {doneCount} of {total} done
        </AppText>

        {/* Derived from the ticks, never stored — the same rule the collection
            screens' progress lines follow. */}
        <View
          style={{
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.surfaceVariant,
            overflow: 'hidden',
            marginBottom: spacing.xxl - 2,
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

      <Reveal index={1}>
        <SectionLabel>Exercises</SectionLabel>
      </Reveal>
      <View style={{ gap: spacing.smd }}>
        {entities.map((entity, i) => {
          const done = completed.has(entity.entityKey);
          const rest = typeof entity.fields.rest === 'string' ? entity.fields.rest : null;
          const restUsable = rest && rest.trim() && rest.trim().toLowerCase() !== '[unclear]';
          const meta = entityMetaLine(type, null, entity.fields);
          const cues = entityDetailFields(type, entity.fields).find((f) => f.label === 'Technique');

          return (
            <Reveal key={entity.entityKey} index={i}>
              <Card radius={radius.md}>
                <Touchable
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: done }}
                  accessibilityLabel={`${entity.name}: ${done ? 'done' : 'not done'}. Tap to toggle.`}
                  onPress={() => toggle(entity.entityKey)}
                  haptic="medium"
                  style={{ flexDirection: 'row', alignItems: 'flex-start', gap: spacing.smd }}
                >
                  <Glyph
                    name={done ? 'checkSquare' : 'square'}
                    size={20}
                    color={done ? palette.accent : palette.textMuted}
                  />
                  <View style={{ flex: 1 }}>
                    <AppText
                      variant="cardTitle"
                      tone={done ? 'muted' : undefined}
                      style={done ? { textDecorationLine: 'line-through' } : undefined}
                    >
                      {entity.name}
                    </AppText>
                    {meta ? (
                      <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
                        {meta}
                      </AppText>
                    ) : null}
                    {cues ? (
                      <AppText variant="bodySmall" tone="muted" style={{ marginTop: spacing.xs }}>
                        {cues.value}
                      </AppText>
                    ) : null}
                  </View>
                </Touchable>
                {restUsable ? <RestTimer label={rest.trim()} /> : null}
              </Card>
            </Reveal>
          );
        })}
      </View>

      {finished ? (
        <Reveal index={2}>
          <Card style={{ marginTop: spacing.lg }}>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Every exercise done
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              Nothing was saved — a session is a thing you did, not a fact about your library.
            </AppText>
            <Touchable accessibilityRole="button" accessibilityLabel="Finish session" onPress={() => router.back()} haptic="medium">
              <AppText variant="label" tone="accent">
                Finish
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}
    </Screen>
  );
}
