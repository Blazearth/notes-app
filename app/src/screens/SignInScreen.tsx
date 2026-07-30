import React, { useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Platform, TextInput, View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Screen } from '@/components/Screen';
import { Touchable } from '@/components/Touchable';
import { useSession } from '@/auth/SessionProvider';
import { useTheme } from '@/theme/ThemeProvider';

type Mode = 'signIn' | 'signUp';

export function SignInScreen() {
  const { palette, radius, spacing } = useTheme();
  const { signIn, signUp } = useSession();

  const [mode, setMode] = useState<Mode>('signIn');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [isError, setIsError] = useState(false);

  const canSubmit = email.trim().length > 3 && password.length >= 6 && !busy;

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
        const { needsConfirmation } = await signUp(email, password);
        if (needsConfirmation) {
          setMessage(
            'Account created. Confirm the email address before signing in — this project has email confirmation on.',
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
          <AppText variant="display" style={{ marginBottom: spacing.sm }}>
            Weavr
          </AppText>
          <AppText tone="muted">
            Save anything. AI organizes everything. Act on anything.
          </AppText>
        </View>

        <Card padding={spacing.lg} style={{ gap: spacing.md }}>
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
