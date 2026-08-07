import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { ApiError } from '@/api/client';
import type { SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { repo, type KnowledgeGroup } from '@/data';
import { STATUS_LABELS } from '@/saves/format';
import { useTheme } from '@/theme/ThemeProvider';

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

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${group.name}, ${group.itemCount} items`}
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
              ? `${childCount} ${childCount === 1 ? 'subgroup' : 'subgroups'} · ${group.itemCount} items`
              : `${group.itemCount} items`}
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

  const [group, setGroup] = useState<KnowledgeGroup | null>(null);
  const [saves, setSaves] = useState<SaveResponse[]>([]);
  const [error, setError] = useState<string | null>(null);
  // K5: the only group this applies to is workout — the local-compute
  // alternative to AI synthesis (docs/knowledge-collections.md, K5) needs at
  // least two sources selected, so this is multi-select scoped to one type
  // rather than a general group feature.
  const [comparing, setComparing] = useState(false);
  const [compareIds, setCompareIds] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setError(null);
    try {
      // Both at once: they are independent, and serialising them would show
      // the header a beat before the list for no reason.
      const [detail, items] = await Promise.all([repo.getGroup(id), repo.listGroupSaves(id)]);
      setGroup(detail);
      setSaves(items);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not open that group');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const isWorkoutGroup = id === 'workout' || id.startsWith('workout~');

  const toggleCompareSelection = useCallback((saveId: string) => {
    setCompareIds((current) => {
      const next = new Set(current);
      if (next.has(saveId)) next.delete(saveId);
      else next.add(saveId);
      return next;
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
          {isWorkoutGroup && !hasSubgroups && readyWorkoutCount >= 2 ? (
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
            ? `${compareIds.size} selected — pick at least 2 workouts to compare`
            : (group.description ??
              (hasSubgroups
                ? `${group.subgroups.length} subgroups · ${group.itemCount} items`
                : `${group.itemCount} items`))}
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
        <SectionLabel>{hasSubgroups ? 'Also in this group' : 'Items'}</SectionLabel>
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
                    isWorkoutGroup && !comparing && save.status === 'ready'
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
