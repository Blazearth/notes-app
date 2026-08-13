import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Touchable } from '@/components/Touchable';
import { useSession } from '@/auth/SessionProvider';
import { supabase } from '@/auth/supabase';
import { useTheme } from '@/theme/ThemeProvider';

type Status = 'exchanging' | 'error';

/**
 * Landed on after tapping an email verification (or password-reset) link —
 * `/auth/callback`, matching `getAuthCallbackUrl`. Supabase's own Auth server
 * validates the token before ever redirecting here; this screen's only job is
 * turning the `code` it hands back into a local session (`detectSessionInUrl:
 * false` on the client means nothing does that automatically), and giving the
 * user somewhere to go when it can't.
 *
 * Three outcomes land here, distinguished by query params:
 * - `?code=...` — a fresh, valid link. Exchanged via PKCE; success flows
 *   through `SessionProvider`'s own `onAuthStateChange`, not a local
 *   `setSession` call, so there is exactly one place a session is adopted.
 * - `?error=...&error_description=...` — Supabase already rejected the link
 *   (expired or already used) before issuing a code at all.
 * - neither — the code was valid but `exchangeCodeForSession` itself failed.
 *   The common real-world cause is opening the link on a different device
 *   than the one that signed up: the PKCE verifier lives in that device's
 *   AsyncStorage, not in this email. The account is still verified server-side
 *   at that point (verification happens before the redirect), so the fix is
 *   just "sign in normally here," not "verification failed."
 */
export function AuthCallbackScreen() {
  const { code, error, error_description } = useLocalSearchParams<{
    code?: string;
    error?: string;
    error_description?: string;
  }>();
  const { session } = useSession();
  const router = useRouter();
  const { palette, spacing } = useTheme();

  const [status, setStatus] = useState<Status>('exchanging');
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (session) return;

    const description = error_description ?? error;
    if (description) {
      setStatus('error');
      setMessage(decodeURIComponent(description).replace(/\+/g, ' '));
      return;
    }

    if (!code) {
      setStatus('error');
      setMessage('This link is missing its verification code.');
      return;
    }

    let cancelled = false;
    supabase.auth.exchangeCodeForSession(code).then(({ error: exchangeError }) => {
      if (cancelled || !exchangeError) return;
      setStatus('error');
      setMessage(
        'Could not finish sign-in from this link. If you opened it on a ' +
          'different device than the one you signed up on, your email is ' +
          'already verified — just sign in below.',
      );
    });
    return () => {
      cancelled = true;
    };
    // Only re-run if the params or session actually change; exchanging twice
    // for the same code would just fail the second time for nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, error, error_description, session]);

  // The exchange (or an already-restored session from a prior tap of this
  // same link) lands here — SessionProvider's own listener is what actually
  // set it, this just stops rendering the callback screen once it has.
  if (session) return <Redirect href="/" />;

  if (status === 'error') {
    return (
      <View
        style={{
          flex: 1,
          alignItems: 'center',
          justifyContent: 'center',
          padding: spacing.xl,
          gap: spacing.md,
          backgroundColor: palette.background,
        }}
      >
        <AppText variant="heading">Verification link didn't work</AppText>
        <AppText tone="muted" style={{ textAlign: 'center' }}>
          {message ?? 'This link may have expired or already been used.'}
        </AppText>
        <Touchable
          accessibilityRole="button"
          haptic="medium"
          onPress={() => router.replace('/sign-in')}
          style={{
            marginTop: spacing.md,
            paddingVertical: spacing.md,
            paddingHorizontal: spacing.xl,
            borderRadius: 999,
            backgroundColor: palette.accent,
          }}
        >
          <AppText variant="label" style={{ color: palette.onAccent }}>
            Back to sign in
          </AppText>
        </Touchable>
      </View>
    );
  }

  return (
    <View
      style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        gap: spacing.md,
        backgroundColor: palette.background,
      }}
    >
      <ActivityIndicator color={palette.accent} />
      <AppText tone="muted">Finishing sign-in…</AppText>
    </View>
  );
}
