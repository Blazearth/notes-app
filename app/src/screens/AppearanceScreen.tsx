import { LinearGradient } from 'expo-linear-gradient';
import { useRouter } from 'expo-router';
import React from 'react';
import { Pressable, ScrollView, TextInput, View, useColorScheme } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Segmented } from '@/components/Segmented';
import { SettingSwitch } from '@/components/SettingRow';
import { usePreferences } from '@/prefs/PreferencesProvider';
import { NAV_BAR_STYLES, THEME_MODES, type NavBarStyle, type ThemeMode } from '@/prefs/types';
import { withAlpha } from '@/theme/contrast';
import { COVER_IDS, COVERS, type CoverId } from '@/theme/covers';
import {
  ACCENT_IDS,
  ACCENTS,
  SURFACE_FAMILIES,
  SURFACE_FAMILY_IDS,
  buildPalette,
  type AccentId,
  type SurfaceFamilyId,
} from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';

const THEME_MODE_LABELS: Record<ThemeMode, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
};

const NAV_STYLE_LABELS: Record<NavBarStyle, string> = {
  floating: 'Floating',
  normal: 'Docked',
};

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  const { spacing } = useTheme();
  return (
    <View style={{ marginBottom: spacing.xxl }}>
      <SectionLabel>{title}</SectionLabel>
      {children}
    </View>
  );
}

/**
 * Live specimen. Renders the accent, a card, a nav pill and the FAB with the
 * *current* preferences, so a change is visible without leaving the screen.
 */
function Preview() {
  const { palette, radius, spacing, type } = useTheme();
  return (
    <View
      style={{
        borderRadius: radius.lg,
        backgroundColor: palette.background,
        borderWidth: 1,
        borderColor: palette.border,
        padding: spacing.md,
        gap: spacing.smd,
        overflow: 'hidden',
      }}
    >
      <View style={{ flexDirection: 'row', gap: spacing.smd }}>
        <View
          style={{
            flex: 1,
            backgroundColor: palette.surface,
            borderWidth: 1,
            borderColor: palette.border,
            borderRadius: radius.md,
            padding: spacing.smd,
          }}
        >
          <AppText variant="cardTitle">Miso ramen</AppText>
          <View style={{ height: 4, borderRadius: 2, backgroundColor: palette.border, marginTop: 6 }}>
            <View
              style={{ width: '70%', height: '100%', borderRadius: 2, backgroundColor: palette.accent }}
            />
          </View>
        </View>
        <View
          style={{
            flex: 1,
            backgroundColor: palette.accentContainer,
            borderRadius: radius.md,
            padding: spacing.smd,
          }}
        >
          <AppText variant="cardTitle" tone="onAccentContainer">
            Digest
          </AppText>
          <AppText variant="caption" tone="onAccentContainer" numberOfLines={2}>
            14 items this week
          </AppText>
        </View>
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
        <View
          style={{
            flex: 1,
            flexDirection: 'row',
            backgroundColor: withAlpha(palette.navBg, 0.94),
            borderRadius: radius.pill,
            padding: 5,
          }}
        >
          {['Home', 'Library'].map((label, index) => (
            <View
              key={label}
              style={{
                flex: 1,
                alignItems: 'center',
                paddingVertical: 7,
                borderRadius: radius.pill,
                backgroundColor: index === 0 ? palette.navActiveBg : 'transparent',
              }}
            >
              <AppText
                style={[
                  type.navLabel,
                  { color: index === 0 ? palette.navActiveText : palette.navInactiveText },
                ]}
              >
                {label}
              </AppText>
            </View>
          ))}
        </View>
        <View
          style={{
            width: 36,
            height: 36,
            borderRadius: 18,
            backgroundColor: palette.accent,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="plus" size={12} weight={2} color={palette.onAccent} />
        </View>
      </View>
    </View>
  );
}

function AccentSwatch({
  id,
  selected,
  isDark,
  family,
  onPress,
}: {
  id: AccentId;
  selected: boolean;
  isDark: boolean;
  family: SurfaceFamilyId;
  onPress: () => void;
}) {
  const { palette, spacing } = useTheme();
  const preview = buildPalette({ family, accent: id, isDark, amoled: false });

  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      accessibilityLabel={ACCENTS[id].label}
      onPress={onPress}
      style={{ alignItems: 'center', gap: spacing.xs, width: 56 }}
    >
      <View
        style={{
          width: 44,
          height: 44,
          borderRadius: 22,
          backgroundColor: preview.accent,
          borderWidth: selected ? 3 : 1,
          borderColor: selected ? palette.text : palette.border,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {selected ? (
          <View
            style={{
              width: 12,
              height: 12,
              borderRadius: 6,
              backgroundColor: preview.onAccent,
            }}
          />
        ) : null}
      </View>
      <AppText variant="caption" tone={selected ? 'default' : 'muted'} style={{ fontSize: 10 }}>
        {ACCENTS[id].label}
      </AppText>
    </Pressable>
  );
}

function FamilyCard({
  id,
  selected,
  isDark,
  accent,
  onPress,
}: {
  id: SurfaceFamilyId;
  selected: boolean;
  isDark: boolean;
  accent: AccentId;
  onPress: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const preview = buildPalette({ family: id, accent, isDark, amoled: false });
  const family = SURFACE_FAMILIES[id];

  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      onPress={onPress}
      style={{
        flex: 1,
        borderRadius: radius.md,
        borderWidth: selected ? 2 : 1,
        borderColor: selected ? palette.accent : palette.border,
        backgroundColor: palette.surface,
        padding: spacing.smd,
        gap: spacing.sm,
      }}
    >
      <View style={{ flexDirection: 'row', gap: 4 }}>
        {[preview.background, preview.surface, preview.surfaceVariant, preview.accent].map((color) => (
          <View
            key={color}
            style={{
              flex: 1,
              height: 22,
              borderRadius: 5,
              backgroundColor: color,
              borderWidth: 1,
              borderColor: preview.border,
            }}
          />
        ))}
      </View>
      <AppText variant="cardTitle">{family.label}</AppText>
      <AppText variant="caption" tone="muted">
        {family.description}
      </AppText>
    </Pressable>
  );
}

function CoverChip({
  id,
  selected,
  onPress,
}: {
  id: CoverId;
  selected: boolean;
  onPress: () => void;
}) {
  const { palette, radius, spacing, alpha } = useTheme();
  const spec = COVERS[id];

  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      accessibilityLabel={spec.label}
      onPress={onPress}
      style={{ alignItems: 'center', gap: spacing.xs, width: 76 }}
    >
      <View
        style={{
          width: 72,
          height: 48,
          borderRadius: radius.sm,
          overflow: 'hidden',
          borderWidth: selected ? 2 : 1,
          borderColor: selected ? palette.accent : palette.border,
          backgroundColor: palette.surfaceVariant,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {spec.colors.length >= 2 ? (
          <LinearGradient
            colors={spec.colors as [string, string, ...string[]]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 0 }}
            style={{ width: '100%', height: '100%', opacity: alpha.coverPreview }}
          />
        ) : (
          <Glyph name="diamond" size={16} />
        )}
      </View>
      <AppText variant="caption" tone={selected ? 'default' : 'muted'} style={{ fontSize: 10 }}>
        {spec.label}
      </AppText>
    </Pressable>
  );
}

export function AppearanceScreen() {
  const { palette, radius, spacing, layout, icon } = useTheme();
  const { prefs, setPreference, resetPreferences } = usePreferences();
  const systemScheme = useColorScheme();
  const router = useRouter();

  const isDark = prefs.themeMode === 'system' ? systemScheme === 'dark' : prefs.themeMode === 'dark';

  return (
    <Screen reserveNavSpace={false}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
          marginBottom: spacing.xl,
        }}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back"
          onPress={() => router.back()}
          style={({ pressed }) => [
            {
              width: 36,
              height: 36,
              borderRadius: radius.sm,
              backgroundColor: palette.surface,
              borderWidth: 1,
              borderColor: palette.border,
              alignItems: 'center',
              justifyContent: 'center',
            },
            pressed && { opacity: 0.7 },
          ]}
        >
          <Glyph name="diamond" size={icon.sm} />
        </Pressable>
        <AppText variant="display">Appearance</AppText>
      </View>

      <View style={{ marginBottom: spacing.xxl }}>
        <Preview />
      </View>

      <Group title="Theme">
        <Segmented
          options={THEME_MODES.map((mode) => ({ value: mode, label: THEME_MODE_LABELS[mode] }))}
          value={prefs.themeMode}
          onChange={(value) => setPreference('themeMode', value)}
        />
        <SettingSwitch
          title="AMOLED black"
          description={
            isDark
              ? 'True black surfaces — saves power on OLED panels.'
              : 'Applies in dark mode only.'
          }
          value={prefs.amoled}
          onValueChange={(value) => setPreference('amoled', value)}
          disabled={!isDark}
        />
      </Group>

      <Group title="Palette">
        <View style={{ flexDirection: 'row', gap: spacing.smd }}>
          {SURFACE_FAMILY_IDS.map((id) => (
            <FamilyCard
              key={id}
              id={id}
              selected={prefs.surfaceFamily === id}
              isDark={isDark}
              accent={prefs.accent}
              onPress={() => setPreference('surfaceFamily', id)}
            />
          ))}
        </View>
      </Group>

      <Group title="Accent">
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md, rowGap: spacing.lg }}>
          {ACCENT_IDS.map((id) => (
            <AccentSwatch
              key={id}
              id={id}
              selected={prefs.accent === id}
              isDark={isDark}
              family={prefs.surfaceFamily}
              onPress={() => setPreference('accent', id)}
            />
          ))}
        </View>
      </Group>

      <Group title="Cover">
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={{ marginHorizontal: -layout.screenGutter }}
          contentContainerStyle={{ paddingHorizontal: layout.screenGutter, gap: spacing.smd }}
        >
          {COVER_IDS.map((id) => (
            <CoverChip
              key={id}
              id={id}
              selected={prefs.cover === id}
              onPress={() => setPreference('cover', id)}
            />
          ))}
        </ScrollView>
      </Group>

      <Group title="Typeface">
        <Segmented
          options={[
            { value: 'sora', label: 'Sora' },
            { value: 'system', label: 'System' },
          ]}
          value={prefs.font}
          onChange={(value) => setPreference('font', value)}
        />
        <Card style={{ marginTop: spacing.md }}>
          <AppText variant="title" style={{ marginBottom: spacing.xs }}>
            Save anything
          </AppText>
          <AppText tone="muted">
            AI organizes everything. Act on anything. The quick brown fox jumps over 13 lazy dogs.
          </AppText>
        </Card>
      </Group>

      <Group title="Navigation">
        <Segmented
          options={NAV_BAR_STYLES.map((style) => ({ value: style, label: NAV_STYLE_LABELS[style] }))}
          value={prefs.navBarStyle}
          onChange={(value) => setPreference('navBarStyle', value)}
        />
        <SettingSwitch
          title="Blur effects"
          description="Translucent nav bar and sheets. Turn off for flat fills and fewer dropped frames on older devices."
          value={prefs.blurEffects}
          onValueChange={(value) => setPreference('blurEffects', value)}
        />
      </Group>

      <Group title="Capture">
        <SettingSwitch
          title="Open app when saving"
          description="Off means a share stays silent — a brief confirmation and you are back in the app you shared from. Weavr does the rest server-side."
          value={prefs.openAppWhenSaving}
          onValueChange={(value) => setPreference('openAppWhenSaving', value)}
        />
      </Group>

      <Group title="Greeting">
        <Card>
          <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.sm }}>
            Name shown on Home
          </AppText>
          <TextInput
            value={prefs.userName}
            onChangeText={(value) => setPreference('userName', value)}
            placeholder="Maya"
            placeholderTextColor={palette.textFaint}
            style={{
              color: palette.text,
              fontSize: 15,
              paddingVertical: spacing.sm,
              paddingHorizontal: spacing.md,
              borderRadius: radius.sm,
              backgroundColor: palette.surfaceVariant,
            }}
          />
        </Card>
      </Group>

      <Pressable
        accessibilityRole="button"
        onPress={resetPreferences}
        style={({ pressed }) => [
          {
            alignSelf: 'flex-start',
            paddingVertical: spacing.smd,
            paddingHorizontal: spacing.lg,
            borderRadius: radius.pill,
            borderWidth: 1,
            borderColor: palette.border,
          },
          pressed && { opacity: 0.7 },
        ]}
      >
        <AppText variant="label" tone="muted">
          Reset to defaults
        </AppText>
      </Pressable>
    </Screen>
  );
}
