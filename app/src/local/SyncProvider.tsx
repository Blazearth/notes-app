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
    void sync
      .bootstrap(userId)
      .catch(() => {
        // A store that cannot be read is a cold start, not a failed sign-in.
      })
      .then(() => {
        if (cancelled) return;
        setReady(true);
        // Fire-and-forget: every task writes to the store, and every screen is
        // already subscribed to what it reads.
        void sync.syncAll();
      });

    return () => {
      cancelled = true;
    };
  }, [userId]);

  return <BootstrapContext.Provider value={ready}>{children}</BootstrapContext.Provider>;
}

/** True once the store is known to belong to the signed-in user. */
export function useStoreReady(): boolean {
  return useContext(BootstrapContext);
}
