import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import type { SaveResponse } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { useLive, useLiveValue } from '@/local';
import { DERIVED_TABLES, readGroup, readGroupSaves } from '@/local/derived';
import { useTaskStatus } from '@/local/useSync';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { type KnowledgeGroup } from '@/data';
import { displayName } from '@/knowledge/facets';
import { groupItemNoun } from '@/saves/saveTypeMeta';
import { STATUS_LABELS } from '@/saves/format';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for the "store has nothing yet" case — see `useLiveValue`. */
const EMPTY_SAVES: SaveResponse[] = [];

/**
 * Three is the practical ceiling for the compare table on a phone-width
 * screen — see `WorkoutCompareScreen`. Capping selection here, rather than
 * letting the table degrade under an arbitrary N, keeps every comparison
 * legible without horizontal scrolling.
 */
const MAX_COMPARE = 3;

/**
 * One row in the subgroup list.
 *
 * A row rather than a tile: subgroup names run to three words ("Research
 * Papers", "YouTube Videos") and a grid of those is mostly truncation. The
 * chevron and the count carry the affordance instead.
 */
const SubgroupRow = React.memo(function SubgroupRow({
  group,
  onPress,
}: {
  group: KnowledgeGroup;
  onPress: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const childCount = group.subgroups.length;
  // A subgroup's id is always `${type}~${slug}` — the same type as its parent
  // (see `@/groups/tree`'s `ID_SEPARATOR`), so the noun for what it holds can
  // be read off the id itself with no extra prop threaded down.
  const noun = groupItemNoun(group.id.split('~')[0], group.itemCount);

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${group.name}, ${group.itemCount} ${noun}`}
        onPress={onPress}
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
            width: 34,
            height: 34,
            borderRadius: radius.sm,
            backgroundColor: palette.surfaceVariant,
            borderWidth: 1,
            borderColor: palette.border,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="layers" size={15} />
        </View>
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={1}>
            {group.name}
          </AppText>
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {/* A subgroup that has its own subgroups says so, because tapping
                it goes another level down rather than to a list of saves. */}
            {childCount > 0
              ? `${childCount} ${childCount === 1 ? 'subgroup' : 'subgroups'} · ${group.itemCount} ${noun}`
              : `${group.itemCount} ${noun}`}
          </AppText>
        </View>
        {/* The icon set has one chevron and it points left, for back
            navigation. Mirrored rather than added as a second asset — a
            disclosure arrow is the same shape facing the other way. */}
        <View style={{ transform: [{ scaleX: -1 }] }}>
          <Glyph name="chevron" size={icon.sm} />
        </View>
      </Touchable>
    </Card>
  );
});

/**
 * A group, at any depth.
 *
 * There is deliberately no separate "subgroup screen". A subgroup is the same
 * shape as its parent, so tapping one pushes this same route with a different
 * id — which is what makes unlimited nesting free rather than a feature that
 * has to be built. The only thing that changes with depth is the id in the URL.
 */
export function GroupDetailScreen({ id }: { id: string }) {
  const { palette, spacing, radius } = useTheme();
  const router = useRouter();

  // K5: the only group this applies to is workout — the local-compute
  // alternative to AI synthesis (docs/knowledge-collections.md, K5) needs at
  // least two sources selected, so this is multi-select scoped to one type
  // rather than a general group feature.
  const [comparing, setComparing] = useState(false);
  const [compareIds, setCompareIds] = useState<Set<string>>(new Set());

  // Both the group and its saves come from the local store — the group tree is
  // derived (`@/groups/tree`, the port of `GroupService`), and the saves it
  // names are already held. No request, no waterfall, and no stale header: a
  // save whose type or facet changes re-files itself the moment it lands.
  const { data: group, loading } = useLive<KnowledgeGroup | null>(
    DERIVED_TABLES,
    (store) => readGroup(store, id),
    [id],
  );
  const saves = useLiveValue<SaveResponse[]>(
    DERIVED_TABLES,
    (store) => readGroupSaves(store, id),
    EMPTY_SAVES,
    [id],
  );

  // A group is a view over saves, so "not found" and "not synced yet" are the
  // same shape locally. Only the first is an error, and only once the store has
  // had a chance to be filled.
  const savesTask = useTaskStatus('delta', true);
  const error = !loading && group == null && savesTask.completed ? 'That group no longer exists.' : null;

  const isWorkoutGroup = id === 'workout' || id.startsWith('workout~');

  useEffect(() => {
    track(AnalyticsEvent.GroupOpened, { group_key: id });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const toggleCompareSelection = useCallback((saveId: string) => {
    setCompareIds((current) => {
      if (current.has(saveId)) {
        const next = new Set(current);
        next.delete(saveId);
        return next;
      }
      // Silently ignore a 4th tap rather than bumping something else off —
      // the user can see exactly which three are selected and deselect one.
      if (current.size >= MAX_COMPARE) return current;
      return new Set(current).add(saveId);
    });
  }, []);

  const cancelComparing = useCallback(() => {
    setComparing(false);
    setCompareIds(new Set());
  }, []);

  if (error) {
    return (
      <Screen>
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Group unavailable
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

  if (!group) {
    return (
      <Screen>
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      </Screen>
    );
  }

  const hasSubgroups = group.subgroups.length > 0;
  const readyWorkoutCount = saves.filter((s) => s.status === 'ready').length;
  const canCompare = isWorkoutGroup && !hasSubgroups && readyWorkoutCount >= 2;
  // A group's own id (the root, not a subgroup) *is* its type — see
  // `@/groups/tree`'s `buildTypeGroup`, which names the top-level node after
  // the type itself. A subgroup one level down still shares it.
  const type = group.id.split('~')[0];
  const noun = groupItemNoun(type, group.itemCount);

  return (
    <>
      <Screen>
      <Reveal index={0}>
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.md }}>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Back"
            onPress={() => router.back()}
            style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
          >
            <Glyph name="chevron" size={14} />
            <AppText variant="caption" tone="muted">
              Back
            </AppText>
          </Touchable>
          {canCompare ? (
            comparing ? (
              <Touchable accessibilityRole="button" onPress={cancelComparing} haptic="light">
                <AppText variant="caption" tone="accent">
                  Cancel
                </AppText>
              </Touchable>
            ) : (
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Compare workouts"
                onPress={() => setComparing(true)}
                haptic="light"
                style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}
              >
                <Glyph name="activity" size={14} color={palette.accent} />
                <AppText variant="caption" tone="accent">
                  Compare
                </AppText>
              </Touchable>
            )
          ) : null}
        </View>

        <AppText variant="title" style={{ marginBottom: spacing.xs }}>
          {group.name}
        </AppText>
        <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xxl - 2 }}>
          {comparing
            ? `${compareIds.size} selected — pick 2-${MAX_COMPARE} workouts to compare`
            : (group.description ??
              (hasSubgroups
                ? `${group.subgroups.length} subgroups · ${group.itemCount} ${noun}`
                : `${group.itemCount} ${noun}`))}
        </AppText>
      </Reveal>

      {/* Subgroups first, always. The structure is the thing being browsed;
          the loose items at this level are the remainder. */}
      {hasSubgroups ? (
        <Reveal index={1}>
          <SectionLabel>Subgroups</SectionLabel>
          <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
            {group.subgroups.map((sub) => (
              <SubgroupRow
                key={sub.id}
                group={sub}
                // The same route, one level deeper. This is the whole of the
                // nesting support.
                onPress={() => router.push({ pathname: '/group/[id]', params: { id: sub.id } })}
              />
            ))}
          </View>
        </Reveal>
      ) : null}

      <Reveal index={2}>
        {/* A single-type folder's list is named for what it holds — "Books",
            not "Items" — the same reasoning `collectionTypeMeta.sectionLabel`
            already applies one system over. "Also in this group" stays generic
            because these are specifically the saves no subgroup claimed. */}
        <SectionLabel>{hasSubgroups ? 'Also in this group' : displayName(type)}</SectionLabel>
        {saves.length > 0 ? (
          <View style={{ gap: spacing.smd }}>
            {saves.map((save) => {
              const selectable = comparing && save.status === 'ready';
              return (
                <SaveCard
                  key={save.id}
                  save={save}
                  onPress={
                    selectable
                      ? () => toggleCompareSelection(save.id)
                      : () => router.push({ pathname: '/save/[id]', params: { id: save.id } })
                  }
                  onLongPress={
                    // Same condition the header's Compare/Cancel uses: entering
                    // compare mode anywhere else (one workout, or a root with
                    // subgroups) left no Cancel and nothing to compare against.
                    canCompare && !comparing && save.status === 'ready'
                      ? () => {
                          setComparing(true);
                          toggleCompareSelection(save.id);
                        }
                      : undefined
                  }
                  selectionMode={comparing && save.status === 'ready'}
                  selected={compareIds.has(save.id)}
                  trailing={
                    save.status === 'ready' ? undefined : (
                      <AppText variant="caption" tone="faint">
                        {STATUS_LABELS[save.status]}
                      </AppText>
                    )
                  }
                />
              );
            })}
          </View>
        ) : (
          <Card>
            <AppText variant="caption" tone="muted">
              {hasSubgroups
                ? 'Everything here is filed into a subgroup.'
                : 'Nothing filed here yet.'}
            </AppText>
          </Card>
        )}
      </Reveal>
    </Screen>
    {comparing ? (
      <View
        style={{
          position: 'absolute',
          left: spacing.lg,
          right: spacing.lg,
          bottom: spacing.xl,
        }}
      >
        <Card
          radius={radius.lg}
          onPress={compareIds.size >= 2 ? () => router.push({ pathname: '/compare-workouts', params: { ids: [...compareIds].join(',') } }) : undefined}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'center',
            gap: spacing.sm,
            backgroundColor: compareIds.size >= 2 ? palette.accent : palette.surfaceVariant,
          }}
        >
          <Glyph name="activity" size={16} weight={2} color={compareIds.size >= 2 ? palette.background : palette.textFaint} />
          <AppText variant="label" style={{ color: compareIds.size >= 2 ? palette.background : palette.textFaint }}>
            {compareIds.size >= 2 ? `Compare ${compareIds.size} workouts` : 'Select at least 2'}
          </AppText>
        </Card>
      </View>
    ) : null}
    </>
  );
}
