import { useRouter } from 'expo-router';
import React from 'react';
import { Pressable, ScrollView, View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { HatchThumb } from '@/components/HatchThumb';
import { ListRow } from '@/components/ListRow';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import {
  ACTIVE_SPACES,
  CONTINUE_ITEMS,
  GREETING_NAME,
  RECENTLY_CAPTURED,
  WEEKLY_DIGEST,
  type ContinueItem,
} from '@/data/sampleContent';
import { usePreferences } from '@/prefs/PreferencesProvider';
import { TYPE_COLORS } from '@/theme/palettes';
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

function ContinueCard({ item }: { item: ContinueItem }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <Card padding={0} radius={radius.lg} style={{ width: 156, overflow: 'hidden' }}>
      <HatchThumb label={item.thumbLabel} height={90} radius={0} />
      <View style={{ padding: spacing.smd }}>
        <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }} numberOfLines={1}>
          {item.title}
        </AppText>
        {item.progress !== undefined ? (
          <View style={{ height: 4, borderRadius: 2, backgroundColor: palette.border }}>
            <View
              style={{
                width: `${Math.round(item.progress * 100)}%`,
                height: '100%',
                borderRadius: 2,
                backgroundColor: palette.accent,
              }}
            />
          </View>
        ) : (
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {item.meta}
          </AppText>
        )}
      </View>
    </Card>
  );
}

export function HomeScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const { prefs } = usePreferences();
  const router = useRouter();

  const name = prefs.userName.trim() || GREETING_NAME;
  const greeting = greetingForHour(new Date().getHours());

  return (
    <Screen cover>
      {/* Greeting + avatar */}
      <View
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
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Appearance settings"
          onPress={() => router.push('/appearance')}
          style={{
            width: 40,
            height: 40,
            borderRadius: 20,
            backgroundColor: palette.accentContainer,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="circle" size={icon.md} color={palette.accent} />
        </Pressable>
      </View>

      {/* Ask-or-find bar */}
      <Pressable
        accessibilityRole="search"
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
      </Pressable>

      <SectionLabel>Continue</SectionLabel>
      <View style={{ marginBottom: spacing.xxl - 2 }}>
        <Rail>
          {CONTINUE_ITEMS.map((item) => (
            <ContinueCard key={item.id} item={item} />
          ))}
        </Rail>
      </View>

      <Card variant="accent" padding={spacing.lg} style={{ marginBottom: spacing.xxl - 2 }}>
        {/* On the accent container, not the page — so the label uses the
            container's computed on-colour rather than the accent itself. */}
        <AppText variant="sectionLabel" tone="onAccentContainer" style={{ marginBottom: spacing.sm }}>
          Weekly digest
        </AppText>
        <AppText tone="onAccentContainer">{WEEKLY_DIGEST}</AppText>
      </Card>

      <SectionLabel>Active spaces</SectionLabel>
      <View style={{ flexDirection: 'row', gap: spacing.smd, marginBottom: spacing.xxl - 2 }}>
        {ACTIVE_SPACES.map((space) => (
          <Card key={space.id} radius={radius.md} padding={spacing.md} style={{ flex: 1 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
              <View
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: radius.sm,
                  backgroundColor: palette.surfaceVariant,
                  borderWidth: 1,
                  borderColor: palette.border,
                }}
              />
              <View style={{ flex: 1 }}>
                <AppText variant="cardTitle" numberOfLines={1}>
                  {space.name}
                </AppText>
                <AppText variant="caption" tone="muted">
                  {space.memberCount} members
                </AppText>
              </View>
            </View>
          </Card>
        ))}
      </View>

      <SectionLabel>Recently captured</SectionLabel>
      <View style={{ gap: spacing.smd }}>
        {RECENTLY_CAPTURED.map((save) => (
          <ListRow
            key={save.id}
            title={save.title}
            subtitle={save.source}
            tint={TYPE_COLORS[save.knowledgeType]}
          />
        ))}
      </View>
    </Screen>
  );
}
