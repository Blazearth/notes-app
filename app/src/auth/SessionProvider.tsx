import type { Session } from '@supabase/supabase-js';
import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import { supabase } from './supabase';
import { mirrorShareSession } from '@/share/nativeShareConfig';

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
  const [session, setSession] = useState<Session | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
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
    mirrorShareSession(session?.access_token ?? null, session?.refresh_token ?? null);
  }, [session]);

  // The refresh timer must not run while the app is backgrounded: on native the
  // JS runtime is suspended, so a timer that fires there either does nothing or
  // wakes up to a stale clock. Supabase exposes explicit start/stop for this.
  useEffect(() => {
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
    const { error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    if (error) throw new Error(error.message);
  }, []);

  const signUp = useCallback(async (email: string, password: string) => {
    const { data, error } = await supabase.auth.signUp({ email: email.trim(), password });
    if (error) throw new Error(error.message);
    // With email confirmation enabled, signUp returns a user but no session.
    return { needsConfirmation: data.session == null };
  }, []);

  const signOut = useCallback(async () => {
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
