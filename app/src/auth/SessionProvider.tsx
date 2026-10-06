import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Session } from '@supabase/supabase-js';
import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import { supabase, SUPABASE_AUTH_STORAGE_KEY } from './supabase';
import { getAuthCallbackUrl } from './redirectUrl';
import { getMe, patchUsername } from '@/api/client';
import { clearPendingUsername, readPendingUsername } from './pendingUsername';
import { identify, resetAnalytics, track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { USE_MOCK_DATA } from '@/data/config';
import { MOCK_USER_ID } from '@/data/mockData';
import { mirrorShareSession } from '@/share/nativeShareConfig';
import { usePreferences } from '@/prefs/PreferencesProvider';

/**
 * A session that satisfies the route guards without Supabase being involved.
 *
 * Auth is a backend dependency like any other, so `USE_MOCK_DATA` has to cover
 * it too — otherwise "the frontend runs with no backend" is false at the very
 * first screen, and every UI review starts with a sign-in form and an account
 * that has to exist somewhere real.
 *
 * Cast rather than constructed in full: `Session` carries a dozen fields that
 * only the Supabase client reads, and in this mode nothing ever hands this to
 * the Supabase client. The two things the app itself touches — a truthy
 * session and `user.id` — are both real here.
 */
const MOCK_SESSION = {
  access_token: 'mock-access-token',
  refresh_token: 'mock-refresh-token',
  token_type: 'bearer',
  expires_in: 3600,
  user: { id: MOCK_USER_ID, email: 'maya@weavr.app' },
} as unknown as Session;

export interface SessionContextValue {
  session: Session | null;
  /** False until the stored session has been read from AsyncStorage. */
  hydrated: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string, name?: string) => Promise<{ needsConfirmation: boolean }>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  // Signed in and hydrated from the first frame when mocking, so there is no
  // splash-then-sign-in flicker on the way to Home.
  const [session, setSession] = useState<Session | null>(USE_MOCK_DATA ? MOCK_SESSION : null);
  const [hydrated, setHydrated] = useState(USE_MOCK_DATA);

  useEffect(() => {
    if (USE_MOCK_DATA) return;
    let cancelled = false;

    // The fast path: read the persisted session straight off disk, with no
    // network involved, and hydrate on that alone. `supabase.auth.getSession()`
    // below looks like the same read but is not — see `SUPABASE_AUTH_STORAGE_KEY`
    // for why it can block on a real token-refresh request. A stale/expired
    // token used optimistically for one paint is harmless: every actual API
    // call re-reads the session itself (`authHeader` in `@/api/client`), and if
    // the stored session turns out not to hold up, the real call below and
    // `onAuthStateChange` correct it — including signing out — once they land.
    void AsyncStorage.getItem(SUPABASE_AUTH_STORAGE_KEY)
      .then((raw) => {
        if (cancelled || !raw) return;
        try {
          const stored = JSON.parse(raw) as Session;
          if (stored?.access_token) setSession(stored);
        } catch {
          // Corrupt storage — the real getSession() below is authoritative.
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setHydrated(true);
      });

    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!cancelled) setSession(data.session);
      })
      .catch(() => {
        // A bad stored session should land the user on sign-in, not crash.
      });

    // Fires on sign-in, sign-out, and every token refresh — so this is what
    // keeps the API client's token current.
    const { data: subscription } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next);
    });

    return () => {
      cancelled = true;
      subscription.subscription.unsubscribe();
    };
  }, []);

  // Android's silent-share Activity/WorkManager worker read this from a
  // plain file (see nativeShareConfig.ts) since they run outside the JS
  // runtime and cannot reach AsyncStorage.
  useEffect(() => {
    // Nothing native should be handed a fake token to POST with.
    if (USE_MOCK_DATA) return;
    mirrorShareSession(session?.access_token ?? null, session?.refresh_token ?? null);
  }, [session]);

  // §F: identify on every session establish, not just first sign-in — a
  // token refresh or app restart fires `onAuthStateChange` with the same
  // user, and re-identifying is what merges every device/session into one
  // PostHog person with no alias logic needed.
  const identifiedUserId = useRef<string | null>(null);
  useEffect(() => {
    const userId = session?.user.id ?? null;
    if (userId && identifiedUserId.current !== userId) {
      identify(userId);
      identifiedUserId.current = userId;
    }
  }, [session]);

  // The refresh timer must not run while the app is backgrounded: on native the
  // JS runtime is suspended, so a timer that fires there either does nothing or
  // wakes up to a stale clock. Supabase exposes explicit start/stop for this.
  useEffect(() => {
    if (USE_MOCK_DATA) return;
    const handle = (state: AppStateStatus) => {
      if (state === 'active') {
        void supabase.auth.startAutoRefresh();
      } else {
        void supabase.auth.stopAutoRefresh();
      }
    };

    handle(AppState.currentState);
    const listener = AppState.addEventListener('change', handle);
    return () => listener.remove();
  }, []);

  const { setPreference } = usePreferences();

  // A username chosen at sign-up while email confirmation was pending is
  // registered the first time that account has a session — a sign-in or the
  // confirmation link's callback, whichever comes first.
  const sessionEmail = session?.user.email ?? null;
  useEffect(() => {
    if (USE_MOCK_DATA || !sessionEmail) return;
    let cancelled = false;
    void (async () => {
      const pending = await readPendingUsername(sessionEmail);
      if (!pending || cancelled) return;
      try {
        await patchUsername(pending);
        if (!cancelled) setPreference('userName', pending);
      } catch {
        // Taken by now, or offline. Taken is final — the user picks another in
        // Settings; offline is retried on the next session start.
        return;
      }
      await clearPendingUsername();
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionEmail, setPreference]);

  const signIn = useCallback(async (email: string, password: string) => {
    if (USE_MOCK_DATA) {
      setSession(MOCK_SESSION);
      track(AnalyticsEvent.SignInCompleted, {});
      return;
    }
    const { error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) throw new Error(error.message);
    track(AnalyticsEvent.SignInCompleted, {});
    // Sync the server-authoritative username to local prefs so returning
    // users see their username on any device without re-entering it. Set even
    // when empty: prefs outlive sign-out, so skipping it greeted a second
    // account on this device by the first account's name. A name still waiting
    // from sign-up is registered by the effect below, which then sets it.
    try {
      const me = await getMe();
      setPreference('userName', me.username ?? '');
    } catch {
      // Non-fatal — prefs.userName may already be set, or the user will
      // see the email fallback until they visit Settings.
    }
  }, [setPreference]);

  const signUp = useCallback(async (email: string, password: string, name?: string) => {
    if (USE_MOCK_DATA) {
      setSession(MOCK_SESSION);
      track(AnalyticsEvent.SignInCompleted, {});
      return { needsConfirmation: false };
    }
    const trimmedName = name?.trim();
    const { data, error } = await supabase.auth.signUp({
      email: email.trim(),
      password,
      options: {
        // Stored as Supabase user metadata (`raw_user_meta_data`). Not read by
        // anything server-side yet — `ProfileService.ensureExists` only inserts
        // the id, so `profiles.display_name` stays null regardless. Harmless to
        // send now and there for a future backend read; the local greeting
        // (`prefs.userName`, set right after this call) is what actually fixes
        // the "shows Maya" bug today.
        data: trimmedName ? { full_name: trimmedName } : undefined,
        // Without this, Supabase falls back to the project's dashboard-configured
        // Site URL for the confirmation email's link — which is `localhost` on
        // an unconfigured project. This is what actually brings the user back
        // into the app; see `getAuthCallbackUrl` and `app/README.md` for the
        // matching Redirect URLs allow-list entry this depends on.
        emailRedirectTo: getAuthCallbackUrl(),
      },
    });
    if (error) throw new Error(error.message);
    // With email confirmation enabled, signUp returns a user but no session —
    // no session means no value delivered yet, so this is not the activation
    // anchor until a real session exists.
    if (data.session != null) track(AnalyticsEvent.SignInCompleted, {});
    return { needsConfirmation: data.session == null };
  }, []);

  const signOut = useCallback(async () => {
    identifiedUserId.current = null;
    resetAnalytics();
    // The greeting belongs to the account, not the device.
    setPreference('userName', '');
    if (USE_MOCK_DATA) {
      setSession(null);
      return;
    }
    await supabase.auth.signOut();
  }, [setPreference]);

  const value = useMemo(
    () => ({ session, hydrated, signIn, signUp, signOut }),
    [session, hydrated, signIn, signUp, signOut],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside <SessionProvider>');
  return ctx;
}
