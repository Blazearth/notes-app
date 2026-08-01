import { useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { Platform, View } from 'react-native';

import { getMe } from '@/api/client';
import type { MeResponse } from '@/api/types';
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

/** One metered allowance. Rendered only when there is a real limit to show. */
function UsageBar({ label, used, limit }: { label: string; used: number; limit: number }) {
  const { palette, spacing } = useTheme();
  // Clamped: the cap is checked before a save is classified, but the counter is
  // incremented after, so a race can land it a hair over. A bar spilling past
  // its track would look like a bug in the meter rather than a full allowance.
  const fraction = limit > 0 ? Math.min(1, used / limit) : 0;

  return (
    <View style={{ marginBottom: spacing.sm }}>
      <View
        style={{
          height: 6,
          borderRadius: 3,
          backgroundColor: palette.accent,
          opacity: 0.25,
          marginBottom: spacing.xs,
        }}
      >
        <View
          style={{
            width: `${fraction * 100}%`,
            height: '100%',
            borderRadius: 3,
            backgroundColor: palette.accent,
          }}
        />
      </View>
      <AppText variant="caption" tone="onAccentContainer">
        {label}: {used} of {limit}
      </AppText>
    </View>
  );
}

/**
 * The plan, from the server rather than from the RevenueCat SDK.
 *
 * The two can disagree — a webhook that has not landed, a receipt validated on
 * another device — and the server's answer is the one that governs what
 * actually happens. A card reading "Pro" while every save is refused is worse
 * than one that lags by a few seconds.
 *
 * A limit of `-1` means unlimited: either the user is Pro, or caps are not
 * being enforced yet. In both cases a usage meter would be meaningless, so
 * there isn't one.
 */
function PlanCard({ me }: { me: MeResponse | null }) {
  const { spacing } = useTheme();
  const metered = me !== null && me.savesLimit > 0;

  return (
    <Card variant="accent" style={{ marginBottom: spacing.xxl }}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: metered ? spacing.smd : 0,
        }}
      >
        <AppText variant="cardTitle" tone="onAccentContainer" style={{ fontSize: 13 }}>
          {me?.pro ? 'Weavr Pro' : 'Free plan'}
        </AppText>
        {me && !me.pro ? (
          <AppText
            variant="label"
            tone="onAccentContainer"
            style={{ fontSize: 11.5, fontWeight: '600' }}
          >
            Upgrade
          </AppText>
        ) : null}
      </View>

      {metered && me ? (
        <>
          <UsageBar label="AI saves this month" used={me.savesUsed} limit={me.savesLimit} />
          <UsageBar label="Shopping lists this week" used={me.actsUsed} limit={me.actsLimit} />
        </>
      ) : null}

      {me?.pro ? (
        <AppText variant="caption" tone="onAccentContainer">
          {me.renewsAt
            ? `Renews ${new Date(me.renewsAt).toLocaleDateString()}`
            : 'Unlimited saves and shopping lists'}
        </AppText>
      ) : null}

      {me && !me.pro && !metered ? (
        <AppText variant="caption" tone="onAccentContainer">
          Unlimited while Weavr is in development.
        </AppText>
      ) : null}
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

  // Null until it loads, and null again if it fails. PlanCard renders the
  // heading either way — a settings screen that shows nothing where the plan
  // should be reads as broken, and the plan is not what the user came here to
  // change.
  const [me, setMe] = useState<MeResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMe()
      .then((response) => {
        if (!cancelled) setMe(response);
      })
      .catch(() => {
        // Deliberately silent. Every other row on this screen is local state
        // that works offline; failing the whole screen because one card could
        // not load would be the wrong trade.
      });
    return () => {
      cancelled = true;
    };
  }, []);

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

      <PlanCard me={me} />

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

      {/*
        "Connected accounts" (Instagram, Google Calendar) lived here and was
        entirely fictional — neither integration exists, and neither row did
        anything. Removed rather than left sitting above real data, the same
        call made for the Library's invented "AI groups" grid.
      */}

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
