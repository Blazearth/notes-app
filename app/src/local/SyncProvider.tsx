/**
 * Ties the session to the store, and kicks off the background sync.
 *
 * It renders nothing of its own and holds no data — screens get their data from
 * the store, not from here. What it owns is the one ordering that must not be
 * got wrong: *bootstrap before render*. Signing in as somebody else has to wipe
 * the previous account's rows before a single screen reads them, and the store
 * is the one thing in the app that survives a sign-out.
 *
 * The sync itself is deliberately **not** awaited. That is the whole shape of
 * this layer: cached data paints immediately, the network fills in behind it.
 */

import React, { createContext, useContext, useEffect, useState } from 'react';

import { useSession } from '@/auth/SessionProvider';
import { sync } from './sync';

const BootstrapContext = createContext(false);

export function SyncProvider({ children }: { children: React.ReactNode }) {
  const { session } = useSession();
  const userId = session?.user.id ?? null;
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;

    if (!userId) {
      // Sign-out. Clear before flipping `ready`, so nothing renders the
      // previous account's library on the way to the sign-in screen.
      setReady(false);
      void sync.reset().finally(() => {
        if (!cancelled) setReady(true);
      });
      return () => {
        cancelled = true;
      };
    }

    setReady(false);
    let stop: (() => void) | null = null;
    void sync
      .bootstrap(userId)
      .catch(() => {
        // A store that cannot be read is a cold start, not a failed sign-in.
      })
      .then(() => {
        if (cancelled) return;
        setReady(true);
        // The triggers: foregrounding and connectivity coming back. Registered
        // here rather than inside the engine's constructor because they are
        // per-signed-in-session — a listener that outlived a sign-out would sync
        // as nobody.
        stop = sync.start();
        // Fire-and-forget: every task writes to the store, and every screen is
        // already subscribed to what it reads.
        void sync.syncAll();
      });

    return () => {
      cancelled = true;
      stop?.();
    };
  }, [userId]);

  /**
   * A refreshed token un-pauses the outbox.
   *
   * The queue pauses rather than failing on a 401 because nothing in it can
   * succeed until the session is valid again, and spending each entry's retry
   * budget discovering that would empty the budget for reasons that have nothing
   * to do with the entries. This is the other half of that: the moment the token
   * changes, the queue is worth trying again. `accessToken` rather than the
   * session object because Supabase hands back a new object on events that did
   * not change the credential.
   */
  useEffect(() => {
    if (session?.access_token) sync.resumeOutbox();
  }, [session?.access_token]);

  return <BootstrapContext.Provider value={ready}>{children}</BootstrapContext.Provider>;
}

/** True once the store is known to belong to the signed-in user. */
export function useStoreReady(): boolean {
  return useContext(BootstrapContext);
}
