import { useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { Alert, Platform, TextInput, View } from 'react-native';

import type { MeResponse } from '@/api/types';
import { ApiError, patchUsername } from '@/api/client';
import { KV, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import { useSession } from '@/auth/SessionProvider';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { PendingWrites } from '@/components/PendingWrites';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { SettingLink, SettingSwitch } from '@/components/SettingRow';
import { Touchable } from '@/components/Touchable';
import { useMorphDismiss } from '@/motion/MorphPresentation';
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

  // Leaving has to collapse the surface back into the gear on Home, not pop the
  // route out from under it. `null` when this screen is reached some other way
  // than the morph — a deep link — where an ordinary back is the right answer.
  const morphDismiss = useMorphDismiss();
  const goBack = morphDismiss ?? (() => router.back());

  // Not backed by an endpoint yet — mirrors the mockup's toggled-on defaults.
  const [pushNotifications, setPushNotifications] = useState(true);
  const [weeklyDigestEmail, setWeeklyDigestEmail] = useState(true);

  // Null until the store has one, and null on a genuinely first run. PlanCard
  // renders the heading either way — a settings screen that shows nothing
  // where the plan should be reads as broken, and the plan is not what the
  // user came here to change.
  //
  // Every other row on this screen is local state that works offline; the plan
  // card now matches, rather than being the one thing that needs a network.
  const me = useLiveValue<MeResponse | null>(['kv'], (store) => store.readKv<MeResponse>(KV.me), null);

  useEffect(() => {
    // Deliberately unawaited and deliberately unhandled: a failure leaves
    // whatever the store already had, which is the right answer here.
    void sync.syncMe();
  }, []);

  const email = session?.user.email ?? '';
  const initial = (prefs.userName || email || 'W').charAt(0).toUpperCase();

  const promptSetUsername = () => {
    let draft = '';
    Alert.prompt(
      prefs.userName ? 'Change username' : 'Set username',
      'Letters, digits and underscores only. 3–20 characters.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Save',
          onPress: async (value: string | undefined) => {
            const trimmed = (value ?? '').trim();
            if (!trimmed) return;
            try {
              await patchUsername(trimmed);
              setPreference('userName', trimmed);
            } catch (e) {
              Alert.alert(
                'Could not set username',
                e instanceof ApiError ? e.message : 'That username may already be taken.',
              );
            }
          },
        },
      ],
      'plain-text',
      prefs.userName,
    );
  };

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
          onPress={goBack}
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
            {prefs.userName ? (
              <AppText variant="caption" tone="accent" numberOfLines={1}>
                @{prefs.userName}
              </AppText>
            ) : (
              <AppText variant="caption" tone="muted" numberOfLines={1}>
                {email || 'Not signed in'}
              </AppText>
            )}
          </View>
          <Touchable
            accessibilityRole="button"
            onPress={promptSetUsername}
            style={{ padding: spacing.xs }}
          >
            <AppText variant="label" tone="accent" style={{ fontSize: 11.5 }}>
              {prefs.userName ? 'Change' : 'Set username'}
            </AppText>
          </Touchable>
        </View>
      </Card>

      <PlanCard me={me} />

      {/* Renders nothing unless the outbox has something to report — see
          `PendingWrites` for why a rejected write is shown rather than
          silently rolled back. */}
      <PendingWrites />

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
