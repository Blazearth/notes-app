import { useRouter } from 'expo-router';
import React, { useState } from 'react';
import { Platform, View } from 'react-native';

import { useSession } from '@/auth/SessionProvider';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { SettingLink, SettingSwitch } from '@/components/SettingRow';
import { Touchable } from '@/components/Touchable';
import { usePreferences } from '@/prefs/PreferencesProvider';
import type { ThemeMode } from '@/prefs/types';
import { useTheme } from '@/theme/ThemeProvider';

const THEME_MODE_LABELS: Record<ThemeMode, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
};

function Row({ children }: { children: React.ReactNode }) {
  const { radius, spacing } = useTheme();
  return (
    <Card radius={radius.md} padding={0} style={{ marginBottom: spacing.sm, paddingHorizontal: spacing.md }}>
      {children}
    </Card>
  );
}

export function SettingsScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const { prefs, setPreference } = usePreferences();
  const { session, signOut } = useSession();
  const router = useRouter();

  // Not backed by an endpoint yet — mirrors the mockup's toggled-on defaults.
  const [pushNotifications, setPushNotifications] = useState(true);
  const [weeklyDigestEmail, setWeeklyDigestEmail] = useState(true);

  const email = session?.user.email ?? '';
  const initial = (email || 'W').charAt(0).toUpperCase();

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
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Back"
          onPress={() => router.back()}
          weight="tile"
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
          <Glyph name="chevron" size={icon.sm} />
        </Touchable>
        <AppText variant="display">Settings</AppText>
      </View>

      <Card style={{ marginBottom: spacing.xxl }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
          <View
            style={{
              width: 52,
              height: 52,
              borderRadius: 26,
              backgroundColor: palette.accentContainer,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <AppText variant="cardTitle" tone="accent" style={{ fontSize: 18 }}>
              {initial}
            </AppText>
          </View>
          <View style={{ flex: 1 }}>
            <AppText variant="cardTitle">{prefs.userName.trim() || 'You'}</AppText>
            <AppText variant="caption" tone="muted" numberOfLines={1}>
              {email || 'Not signed in'}
            </AppText>
          </View>
          <AppText variant="label" tone="accent" style={{ fontSize: 11.5 }}>
            Edit
          </AppText>
        </View>
      </Card>

      <Card variant="accent" style={{ marginBottom: spacing.xxl }}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: spacing.smd,
          }}
        >
          <AppText variant="cardTitle" tone="onAccentContainer" style={{ fontSize: 13 }}>
            Free plan
          </AppText>
          <AppText variant="label" tone="onAccentContainer" style={{ fontSize: 11.5, fontWeight: '600' }}>
            Upgrade
          </AppText>
        </View>
        <View
          style={{
            height: 6,
            borderRadius: 3,
            backgroundColor: palette.accent,
            opacity: 0.25,
            marginBottom: spacing.sm,
          }}
        >
          <View style={{ width: '42%', height: '100%', borderRadius: 3, backgroundColor: palette.accent }} />
        </View>
        <AppText variant="caption" tone="onAccentContainer">
          2.1 GB of 5 GB used
        </AppText>
      </Card>

      <SectionLabel>Preferences</SectionLabel>
      <View style={{ marginBottom: spacing.xxl }}>
        <Row>
          <SettingSwitch
            title="Push notifications"
            value={pushNotifications}
            onValueChange={setPushNotifications}
          />
        </Row>
        <Row>
          <SettingSwitch
            title="Weekly digest email"
            value={weeklyDigestEmail}
            onValueChange={setWeeklyDigestEmail}
          />
        </Row>
        {/* No haptics API on web, so the toggle would be a control that
            demonstrably does nothing. Hide it rather than explain it. */}
        {Platform.OS === 'web' ? null : (
          <Row>
            <SettingSwitch
              title="Haptic feedback"
              description="Vibration on taps, selections and saves"
              value={prefs.haptics}
              onValueChange={(v) => setPreference('haptics', v)}
            />
          </Row>
        )}
        <Row>
          <SettingLink
            title="Appearance"
            value={THEME_MODE_LABELS[prefs.themeMode]}
            onPress={() => router.push('/appearance')}
          />
        </Row>
      </View>

      <SectionLabel>Connected accounts</SectionLabel>
      <View style={{ marginBottom: spacing.xxl }}>
        <Row>
          <SettingLink title="Instagram" value="Connected" onPress={() => {}} />
        </Row>
        <Row>
          <SettingLink title="Google Calendar" value="Connect" onPress={() => {}} />
        </Row>
      </View>

      <SectionLabel>Support</SectionLabel>
      <View style={{ marginBottom: spacing.xxl }}>
        <Row>
          <SettingLink title="Data & privacy" onPress={() => {}} />
        </Row>
        <Row>
          <SettingLink title="Help & support" onPress={() => {}} />
        </Row>
      </View>

      <Touchable
        accessibilityRole="button"
        onPress={() => void signOut()}
        // Signing out is destructive and unprompted — it earns the heavier tap.
        haptic="medium"
        style={{ paddingVertical: spacing.md, alignItems: 'center' }}
      >
        <AppText variant="label" style={{ fontSize: 13, fontWeight: '600', color: palette.danger }}>
          Log out
        </AppText>
      </Touchable>
    </Screen>
  );
}
