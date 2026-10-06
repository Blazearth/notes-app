import React, { useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Platform, TextInput, View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Screen } from '@/components/Screen';
import { Touchable } from '@/components/Touchable';
import { WeavrMark } from '@/components/WeavrMark';
import { ApiError, patchUsername } from '@/api/client';
import { savePendingUsername } from '@/auth/pendingUsername';
import { useSession } from '@/auth/SessionProvider';
import { usePreferences } from '@/prefs/PreferencesProvider';
import { useTheme } from '@/theme/ThemeProvider';

type Mode = 'signIn' | 'signUp';

/** 3-20 chars, letters/digits/underscores, no leading/trailing underscore */
const USERNAME_RE = /^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$|^[a-zA-Z0-9]{3}$/;

export function SignInScreen() {
  const { palette, radius, spacing } = useTheme();
  const { signIn, signUp } = useSession();
  const { setPreference } = usePreferences();

  const [mode, setMode] = useState<Mode>('signIn');
  const [name, setName] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [isError, setIsError] = useState(false);

  const usernameValid = mode === 'signIn' || USERNAME_RE.test(username.trim());

  const canSubmit =
    email.trim().length > 3 &&
    password.length >= 6 &&
    (mode === 'signIn' || (name.trim().length > 0 && usernameValid)) &&
    !busy;

  const submit = async () => {
    setBusy(true);
    setMessage(null);
    setIsError(false);
    try {
      if (mode === 'signIn') {
        await signIn(email, password);
        // A successful sign-in flips the session, and the route guard in
        // `app/sign-in.tsx` redirects — nothing to do here.
      } else {
        const trimmedUsername = username.trim();
        if (!USERNAME_RE.test(trimmedUsername)) {
          setIsError(true);
          setMessage('Username must be 3–20 characters: letters, digits, underscores only.');
          return;
        }

        const { needsConfirmation } = await signUp(email, password, name);

        // Set locally immediately — Home greeting reads this without waiting
        // for the PATCH round-trip.
        setPreference('userName', trimmedUsername);

        if (needsConfirmation) {
          // No session yet, so the server cannot be asked to reserve the name —
          // trying anyway failed `unauthorized` and was reported as "taken".
          // Held until this account first signs in (see `pendingUsername`).
          await savePendingUsername(email, trimmedUsername);
          setMessage('Account created. Check your email and confirm the address, then sign in.');
          return;
        }

        // Register the username server-side (uniqueness enforced there).
        try {
          await patchUsername(trimmedUsername);
        } catch (e) {
          // Don't block sign-up over it — the name can be set from Settings.
          setIsError(true);
          setMessage(
            `Account created, but the username couldn’t be saved: ${
              e instanceof ApiError ? e.message : 'try a different one'
            }. You can set one from Settings.`,
          );
        }
      }
    } catch (e) {
      setIsError(true);
      setMessage(e instanceof Error ? e.message : 'Could not sign in');
    } finally {
      setBusy(false);
    }
  };

  const field = {
    color: palette.text,
    fontSize: 15,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.sm,
    backgroundColor: palette.surfaceVariant,
  } as const;

  return (
    <KeyboardAvoidingView
      style={{ flex: 1 }}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <Screen reserveNavSpace={false}>
        <View style={{ marginTop: spacing.xxxl, marginBottom: spacing.xxl }}>
          <WeavrMark size={36} />
          <AppText variant="display" style={{ marginTop: spacing.md, marginBottom: spacing.sm }}>
            Weavr
          </AppText>
          <AppText tone="muted">
            Save anything. AI organizes everything. Act on anything.
          </AppText>
        </View>

        <Card padding={spacing.lg} style={{ gap: spacing.md }}>
          {mode === 'signUp' ? (
            <>
              <TextInput
                value={name}
                onChangeText={setName}
                placeholder="Your name"
                placeholderTextColor={palette.textFaint}
                autoCapitalize="words"
                autoComplete="name"
                editable={!busy}
                style={field}
              />
              <View>
                <TextInput
                  value={username}
                  onChangeText={(t) => setUsername(t.replace(/\s/g, ''))}
                  placeholder="Username  (e.g. blaze_42)"
                  placeholderTextColor={palette.textFaint}
                  autoCapitalize="none"
                  autoComplete="username-new"
                  autoCorrect={false}
                  editable={!busy}
                  style={[
                    field,
                    username.length > 0 && !usernameValid && { borderWidth: 1, borderColor: palette.danger },
                  ]}
                />
                {username.length > 0 && !usernameValid ? (
                  <AppText variant="caption" style={{ color: palette.danger, marginTop: 4, paddingHorizontal: spacing.xs }}>
                    3–20 chars · letters, digits and _ only · no leading/trailing _
                  </AppText>
                ) : null}
              </View>
            </>
          ) : null}
          <TextInput
            value={email}
            onChangeText={setEmail}
            placeholder="you@example.com"
            placeholderTextColor={palette.textFaint}
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            inputMode="email"
            editable={!busy}
            style={field}
          />
          <TextInput
            value={password}
            onChangeText={setPassword}
            placeholder="Password"
            placeholderTextColor={palette.textFaint}
            autoCapitalize="none"
            autoComplete={mode === 'signIn' ? 'current-password' : 'new-password'}
            secureTextEntry
            editable={!busy}
            onSubmitEditing={() => canSubmit && void submit()}
            style={field}
          />

          <Touchable
            accessibilityRole="button"
            disabled={!canSubmit}
            onPress={() => void submit()}
            // The one commitment on this screen.
            haptic="medium"
            baseOpacity={canSubmit ? 1 : 0.45}
            style={{
              alignItems: 'center',
              justifyContent: 'center',
              minHeight: 46,
              borderRadius: radius.pill,
              backgroundColor: palette.accent,
            }}
          >
            {busy ? (
              <ActivityIndicator color={palette.onAccent} />
            ) : (
              <AppText variant="label" style={{ color: palette.onAccent, fontSize: 14 }}>
                {mode === 'signIn' ? 'Sign in' : 'Create account'}
              </AppText>
            )}
          </Touchable>

          {message ? (
            <AppText
              variant="caption"
              style={{ color: isError ? palette.danger : palette.textMuted }}
            >
              {message}
            </AppText>
          ) : null}
        </Card>

        <Touchable
          accessibilityRole="button"
          onPress={() => {
            setMode((m) => (m === 'signIn' ? 'signUp' : 'signIn'));
            setMessage(null);
          }}
          style={{ alignSelf: 'center', marginTop: spacing.xl, padding: spacing.sm }}
        >
          <AppText variant="bodySmall" tone="accent">
            {mode === 'signIn' ? 'Create an account' : 'I already have an account'}
          </AppText>
        </Touchable>
      </Screen>
    </KeyboardAvoidingView>
  );
}
