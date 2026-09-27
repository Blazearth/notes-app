import { useRouter } from 'expo-router';
import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Linking, Modal, Platform, Pressable, TextInput, View } from 'react-native';

import type { MeResponse } from '@/api/types';
import { ApiError, patchUsername } from '@/api/client';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { repo } from '@/data';
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
import { PRIVACY_POLICY_URL, hasLegalUrl } from '@/legal/links';
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
 *
 * "Upgrade" was a label rather than a control until the paywall existed. It is
 * a button now — and still only rendered for a user who is not already Pro,
 * since there is nothing to sell them.
 */
function PlanCard({ me, onUpgrade }: { me: MeResponse | null; onUpgrade: () => void }) {
  const { spacing, radius, palette } = useTheme();
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
        {/* Rendered for a Pro user too, as "Manage" — that is the only route to
            Restore purchases, which is exactly what someone who has reinstalled
            or switched device needs, and they are the least likely to go
            looking for it behind an "Upgrade" button. */}
        <Touchable
          onPress={onUpgrade}
          accessibilityRole="button"
          accessibilityLabel={me?.pro ? 'Manage your Weavr Pro subscription' : 'Upgrade to Weavr Pro'}
          weight="control"
          haptic="medium"
          style={{
            paddingHorizontal: spacing.md,
            paddingVertical: spacing.xs,
            borderRadius: radius.pill,
            backgroundColor: me?.pro ? 'transparent' : palette.accent,
            borderWidth: me?.pro ? 1 : 0,
            borderColor: palette.onAccentContainer,
          }}
        >
          <AppText
            variant="label"
            tone={me?.pro ? 'onAccentContainer' : 'onAccent'}
            style={{ fontSize: 11.5, fontWeight: '600' }}
          >
            {me?.pro ? 'Manage' : 'Upgrade'}
          </AppText>
        </Touchable>
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

  useEffect(() => {
    track(AnalyticsEvent.ScreenViewed, { screen_name: 'settings' });
  }, []);

  /**
   * Settings is a modal presented *on top of* `index` (`MorphPresentation`),
   * so a `session` flip to null alone does nothing visible: `index`'s own
   * `<Redirect href="/sign-in">` fires, but it is buried under this screen in
   * the stack, and the redirect target ends up hidden underneath rather than
   * shown. Dismissing first, before or alongside the sign-out request, is
   * what makes `index` the topmost route again in time for its own redirect
   * to actually be seen — without this, "Log out" (and now account deletion)
   * leaves the user staring at a Settings screen that has quietly forgotten
   * who they are, rather than landing them on sign-in.
   */
  const leaveAndSignOut = () => {
    goBack();
    void signOut();
  };

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

  // ---- username modal state ----
  const [showUsernameModal, setShowUsernameModal] = useState(false);
  const [usernameInput, setUsernameInput] = useState('');
  const [usernameBusy, setUsernameBusy] = useState(false);
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const usernameRef = useRef<TextInput>(null);

  const USERNAME_RE = /^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$|^[a-zA-Z0-9]{3}$/;
  const usernameInputValid = USERNAME_RE.test(usernameInput.trim());

  const openUsernameModal = () => {
    setUsernameInput(prefs.userName ?? '');
    setUsernameError(null);
    setShowUsernameModal(true);
    // Small delay so the modal is visible before keyboard pops
    setTimeout(() => usernameRef.current?.focus(), 150);
  };

  const saveUsername = async () => {
    const trimmed = usernameInput.trim();
    if (!usernameInputValid) {
      setUsernameError('3–20 chars · letters, digits and _ only · no leading/trailing _');
      return;
    }
    setUsernameBusy(true);
    setUsernameError(null);
    try {
      await patchUsername(trimmed);
      setPreference('userName', trimmed);
      setShowUsernameModal(false);
    } catch (e) {
      setUsernameError(e instanceof ApiError ? e.message : 'That username may already be taken.');
    } finally {
      setUsernameBusy(false);
    }
  };

  // ---- Delete account: two-step confirmation ----
  const [deleteAccountStep, setDeleteAccountStep] = useState<'none' | 'confirm' | 'final'>('none');
  const [deleteAccountText, setDeleteAccountText] = useState('');
  const [deleteAccountBusy, setDeleteAccountBusy] = useState(false);
  const [deleteAccountError, setDeleteAccountError] = useState<string | null>(null);

  const openDeleteAccount = () => {
    setDeleteAccountText('');
    setDeleteAccountError(null);
    setDeleteAccountStep('confirm');
  };

  const closeDeleteAccount = () => {
    if (deleteAccountBusy) return;
    setDeleteAccountStep('none');
  };

  /**
   * Never optimistic, same rule as Space deletion: the account and its data
   * stay intact, and the user stays signed in, until the server actually
   * confirms the delete. Only on success does anything local change — the
   * sign-out that follows is what triggers `SyncProvider`'s existing
   * sign-out effect to wipe the local store and `TabShell` to redirect to
   * sign-in, the same two things "Log out" already does.
   */
  const confirmDeleteAccount = async () => {
    if (deleteAccountBusy) return;
    setDeleteAccountBusy(true);
    setDeleteAccountError(null);
    try {
      await repo.deleteAccount();
      setDeleteAccountStep('none');
      leaveAndSignOut();
    } catch (e) {
      setDeleteAccountError(
        e instanceof ApiError ? e.message : 'Could not delete your account. Check your connection and try again.',
      );
    } finally {
      setDeleteAccountBusy(false);
    }
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
            onPress={openUsernameModal}
            style={{ padding: spacing.xs }}
          >
            <AppText variant="label" tone="accent" style={{ fontSize: 11.5 }}>
              {prefs.userName ? 'Change' : 'Set username'}
            </AppText>
          </Touchable>
        </View>
      </Card>

      <PlanCard
        me={me}
        onUpgrade={() => router.push({ pathname: '/paywall', params: { trigger: 'settings' } })}
      />

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
        {/*
          Rendered only once a real URL is configured (`@/legal/links`). A row
          that opens nothing is the same dead affordance the fictional
          "Connected accounts" rows above were removed for — and Play requires
          the policy to be a hosted page anyway, so there is no in-app screen
          to fall back to.
        */}
        {hasLegalUrl(PRIVACY_POLICY_URL) ? (
          <Row>
            <SettingLink
              title="Privacy policy"
              onPress={() => void Linking.openURL(PRIVACY_POLICY_URL).catch(() => {})}
            />
          </Row>
        ) : null}
        <Row>
          <SettingLink title="Help & support" onPress={() => {}} />
        </Row>
      </View>

      {/* Visually separated from ordinary settings, per the spec's own
          "DANGER ZONE" example — its own labelled section rather than a row
          folded into Support, where an irreversible action would sit beside
          ordinary preference toggles. */}
      <SectionLabel>Danger Zone</SectionLabel>
      <View style={{ marginBottom: spacing.xxl }}>
        <Row>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Delete account"
            onPress={openDeleteAccount}
            haptic="medium"
            style={{ paddingVertical: spacing.md }}
          >
            <AppText variant="bodySmall" style={{ fontSize: 14, fontWeight: '600', color: palette.danger }}>
              Delete account
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
              Permanently delete your Weavr account and associated data.
            </AppText>
          </Touchable>
        </Row>
      </View>

      <Touchable
        accessibilityRole="button"
        onPress={leaveAndSignOut}
        // Signing out is destructive and unprompted — it earns the heavier tap.
        haptic="medium"
        style={{ paddingVertical: spacing.md, alignItems: 'center' }}
      >
        <AppText variant="label" style={{ fontSize: 13, fontWeight: '600', color: palette.danger }}>
          Log out
        </AppText>
      </Touchable>
      {/* ---- Username Modal (cross-platform, works on Android) ---- */}
      <Modal
        visible={showUsernameModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowUsernameModal(false)}
      >
        <KeyboardAvoidingView
          style={{ flex: 1 }}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <Pressable
            style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.55)', justifyContent: 'center', paddingHorizontal: spacing.xl }}
            onPress={() => !usernameBusy && setShowUsernameModal(false)}
          >
            <Pressable onPress={() => {}} style={{ backgroundColor: palette.surface, borderRadius: radius.lg, padding: spacing.xl, gap: spacing.md }}>
              <AppText variant="cardTitle">
                {prefs.userName ? 'Change username' : 'Set username'}
              </AppText>
              <AppText variant="caption" tone="muted">
                Letters, digits and underscores · 3–20 characters · must be unique
              </AppText>

              <TextInput
                ref={usernameRef}
                value={usernameInput}
                onChangeText={(t) => { setUsernameInput(t.replace(/\s/g, '')); setUsernameError(null); }}
                placeholder="e.g. blaze_42"
                placeholderTextColor={palette.textFaint}
                autoCapitalize="none"
                autoCorrect={false}
                editable={!usernameBusy}
                onSubmitEditing={() => void saveUsername()}
                returnKeyType="done"
                style={{
                  color: palette.text,
                  fontSize: 16,
                  paddingVertical: spacing.md,
                  paddingHorizontal: spacing.md,
                  borderRadius: radius.sm,
                  backgroundColor: palette.surfaceVariant,
                  borderWidth: usernameError ? 1 : 0,
                  borderColor: palette.danger,
                }}
              />

              {usernameError ? (
                <AppText variant="caption" style={{ color: palette.danger }}>
                  {usernameError}
                </AppText>
              ) : null}

              <View style={{ flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs }}>
                <Touchable
                  accessibilityRole="button"
                  onPress={() => setShowUsernameModal(false)}
                  disabled={usernameBusy}
                  style={{
                    flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                    borderRadius: radius.pill, backgroundColor: palette.surfaceVariant,
                  }}
                >
                  <AppText variant="label" style={{ fontSize: 14 }}>Cancel</AppText>
                </Touchable>
                <Touchable
                  accessibilityRole="button"
                  onPress={() => void saveUsername()}
                  disabled={usernameBusy || !usernameInputValid}
                  haptic="medium"
                  baseOpacity={usernameBusy || !usernameInputValid ? 0.45 : 1}
                  style={{
                    flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                    borderRadius: radius.pill, backgroundColor: palette.accent,
                  }}
                >
                  {usernameBusy
                    ? <ActivityIndicator color={palette.onAccent} />
                    : <AppText variant="label" style={{ fontSize: 14, color: palette.onAccent }}>Save</AppText>
                  }
                </Touchable>
              </View>
            </Pressable>
          </Pressable>
        </KeyboardAvoidingView>
      </Modal>

      {/* ---- Delete Account Modal: two steps, one component ---- */}
      <Modal
        visible={deleteAccountStep !== 'none'}
        transparent
        animationType="fade"
        onRequestClose={closeDeleteAccount}
      >
        <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <Pressable
            style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.55)', justifyContent: 'center', paddingHorizontal: spacing.xl }}
            onPress={closeDeleteAccount}
          >
            <Pressable onPress={() => {}} style={{ backgroundColor: palette.surface, borderRadius: radius.lg, padding: spacing.xl, gap: spacing.md }}>
              {deleteAccountStep === 'confirm' ? (
                <>
                  <AppText variant="cardTitle">Delete your account?</AppText>
                  <AppText variant="caption" tone="muted">
                    This will permanently delete your Weavr account and associated personal data.{'\n'}
                    You may lose access to Spaces, saved content, preferences, and other account data.
                  </AppText>

                  <View style={{ flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs }}>
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel="Cancel delete account"
                      onPress={closeDeleteAccount}
                      style={{
                        flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                        borderRadius: radius.pill, backgroundColor: palette.surfaceVariant,
                      }}
                    >
                      <AppText variant="label" style={{ fontSize: 14 }}>Cancel</AppText>
                    </Touchable>
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel="Continue to delete account"
                      onPress={() => {
                        setDeleteAccountText('');
                        setDeleteAccountError(null);
                        setDeleteAccountStep('final');
                      }}
                      haptic="medium"
                      style={{
                        flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                        borderRadius: radius.pill, backgroundColor: palette.danger,
                      }}
                    >
                      <AppText variant="label" style={{ fontSize: 14, color: '#ffffff' }}>Continue</AppText>
                    </Touchable>
                  </View>
                </>
              ) : (
                <>
                  <AppText variant="cardTitle">Type DELETE to confirm</AppText>
                  <AppText variant="caption" tone="muted">
                    This is the last step — your account and its data are permanently deleted the moment you confirm.
                  </AppText>

                  <TextInput
                    value={deleteAccountText}
                    onChangeText={(t) => {
                      setDeleteAccountText(t);
                      setDeleteAccountError(null);
                    }}
                    placeholder="DELETE"
                    placeholderTextColor={palette.textFaint}
                    autoCapitalize="characters"
                    autoCorrect={false}
                    editable={!deleteAccountBusy}
                    style={{
                      color: palette.text,
                      fontSize: 16,
                      paddingVertical: spacing.md,
                      paddingHorizontal: spacing.md,
                      borderRadius: radius.sm,
                      backgroundColor: palette.surfaceVariant,
                    }}
                  />

                  {deleteAccountError ? (
                    <AppText variant="caption" style={{ color: palette.danger }}>
                      {deleteAccountError}
                    </AppText>
                  ) : null}

                  <View style={{ flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs }}>
                    <Touchable
                      accessibilityRole="button"
                      onPress={closeDeleteAccount}
                      disabled={deleteAccountBusy}
                      style={{
                        flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                        borderRadius: radius.pill, backgroundColor: palette.surfaceVariant,
                      }}
                    >
                      <AppText variant="label" style={{ fontSize: 14 }}>Cancel</AppText>
                    </Touchable>
                    <Touchable
                      accessibilityRole="button"
                      accessibilityLabel="Confirm delete account"
                      onPress={() => void confirmDeleteAccount()}
                      disabled={deleteAccountBusy || deleteAccountText.trim() !== 'DELETE'}
                      haptic="medium"
                      baseOpacity={deleteAccountBusy || deleteAccountText.trim() !== 'DELETE' ? 0.45 : 1}
                      style={{
                        flex: 1, alignItems: 'center', paddingVertical: spacing.md,
                        borderRadius: radius.pill, backgroundColor: palette.danger,
                      }}
                    >
                      {deleteAccountBusy
                        ? <ActivityIndicator color="#ffffff" />
                        : <AppText variant="label" style={{ fontSize: 14, color: '#ffffff' }}>DELETE</AppText>
                      }
                    </Touchable>
                  </View>
                </>
              )}
            </Pressable>
          </Pressable>
        </KeyboardAvoidingView>
      </Modal>
    </Screen>
  );
}
