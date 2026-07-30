import React, { useState } from 'react';
import { ScrollView, View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Chip } from '@/components/Chip';
import { Glyph } from '@/components/Glyph';
import { ListRow } from '@/components/ListRow';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import {
  AI_GROUPS,
  LIBRARY_FILTERS,
  RECENTLY_ORGANIZED,
  type GroupItem,
  type LibraryFilter,
} from '@/data/sampleContent';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';

function HeaderAction({ glyph, label }: { glyph: 'search' | 'filter'; label: string }) {
  const { palette, radius, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      weight="tile"
      // Neither header action is wired to anything yet.
      haptic={null}
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

function GroupTile({ group }: { group: GroupItem }) {
  const { spacing } = useTheme();
  return (
    <Card
      variant={group.featured ? 'accent' : 'surface'}
      padding={spacing.md + 2}
      style={{ flexGrow: 1, flexBasis: '47%', aspectRatio: 1.3 }}
    >
      <AppText
        variant="cardTitle"
        tone={group.featured ? 'onAccentContainer' : 'default'}
        style={{ fontSize: 13, marginBottom: spacing.xs }}
      >
        {group.name}
      </AppText>
      <AppText variant="caption" tone={group.featured ? 'onAccentContainer' : 'muted'}>
        {group.path}
      </AppText>
      <AppText
        variant="caption"
        tone={group.featured ? 'onAccentContainer' : 'default'}
        style={{ marginTop: spacing.sm, fontWeight: '600' }}
      >
        {group.count} items
      </AppText>
    </Card>
  );
}

export function LibraryScreen() {
  const { layout, spacing } = useTheme();
  const [filter, setFilter] = useState<LibraryFilter>('All');

  const groups = filter === 'All' ? AI_GROUPS : AI_GROUPS.filter((g) => g.category === filter);
  const organized =
    filter === 'All' ? RECENTLY_ORGANIZED : RECENTLY_ORGANIZED.filter((s) => s.category === filter);

  return (
    <Screen>
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
          <HeaderAction glyph="search" label="Search library" />
          <HeaderAction glyph="filter" label="Filter and sort" />
        </View>
      </Reveal>

      <Reveal index={1}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={{ marginHorizontal: -layout.screenGutter, marginBottom: spacing.lg + 2 }}
          contentContainerStyle={{ paddingHorizontal: layout.screenGutter, gap: spacing.sm }}
        >
          {LIBRARY_FILTERS.map((option) => (
            <Chip
              key={option}
              label={option}
              selected={option === filter}
              onPress={() => setFilter(option)}
            />
          ))}
        </ScrollView>
      </Reveal>

      <Reveal index={2}>
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
            <GroupTile key={group.id} group={group} />
          ))}
        </View>
      </Reveal>

      <Reveal index={3}>
        <SectionLabel>Recently organized</SectionLabel>
      </Reveal>
      <View style={{ gap: spacing.smd }}>
        {organized.length > 0 ? (
          organized.map((save, i) => (
            // Keyed by filter as well as id, so switching filters remounts the
            // rows and they animate in. Without the filter in the key, React
            // reuses the surviving rows and a filter change lands silently.
            <Reveal key={`${filter}-${save.id}`} index={i}>
              <ListRow
                title={save.title}
                subtitle={save.source}
                tint={TYPE_COLORS[save.knowledgeType]}
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
                Saves land in {filter} once the pipeline classifies them.
              </AppText>
            </Card>
          </Reveal>
        )}
      </View>
    </Screen>
  );
}
