import type { Session } from '@supabase/supabase-js';
import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import { supabase } from './supabase';
import { USE_MOCK_DATA } from '@/data/config';
import { MOCK_USER_ID } from '@/data/mockData';
import { mirrorShareSession } from '@/share/nativeShareConfig';

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
  signUp: (email: string, password: string) => Promise<{ needsConfirmation: boolean }>;
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

    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!cancelled) setSession(data.session);
      })
      .catch(() => {
        // A bad stored session should land the user on sign-in, not crash.
      })
      .finally(() => {
        if (!cancelled) setHydrated(true);
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

  const signIn = useCallback(async (email: string, password: string) => {
    if (USE_MOCK_DATA) {
      setSession(MOCK_SESSION);
      return;
    }
    const { error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) throw new Error(error.message);
  }, []);

  const signUp = useCallback(async (email: string, password: string) => {
    if (USE_MOCK_DATA) {
      setSession(MOCK_SESSION);
      return { needsConfirmation: false };
    }
    const { data, error } = await supabase.auth.signUp({ email: email.trim(), password });
    if (error) throw new Error(error.message);
    // With email confirmation enabled, signUp returns a user but no session.
    return { needsConfirmation: data.session == null };
  }, []);

  const signOut = useCallback(async () => {
    if (USE_MOCK_DATA) {
      setSession(null);
      return;
    }
    await supabase.auth.signOut();
  }, []);

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
