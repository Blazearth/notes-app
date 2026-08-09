/**
 * Reactive reads from the local store.
 *
 * This is the piece `Repository` could not be. All 48 of its methods return
 * `Promise<T>`, which resolves exactly once — and stale-while-revalidate needs
 * two emissions: the cached answer now, the fresh one when sync lands it. A
 * hook subscribed to the store's change bus gives as many emissions as there
 * are writes, without any screen knowing a sync happened.
 *
 * A screen states which tables it reads. It is re-run when one of them changes
 * and not otherwise, so ticking a shopping item does not re-query the Library.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import { getStore } from './index';
import type { LocalStore, StoreTable } from './store';

export interface LiveResult<T> {
  data: T | undefined;
  /** True only until the *first* read resolves — never again on a refresh. */
  loading: boolean;
  /** Re-runs the read by hand. Rarely needed; writes notify on their own. */
  reload: () => void;
}

export function useLive<T>(
  tables: readonly StoreTable[],
  read: (store: LocalStore) => Promise<T>,
  deps: readonly unknown[] = [],
): LiveResult<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);

  // The reader closes over props and is therefore a new function every render.
  // Keeping it in a ref is what lets the effect depend on `deps` — the values
  // that actually change the query — instead of re-subscribing every render.
  const readRef = useRef(read);
  readRef.current = read;

  // Monotonic, so a slow read that resolves after a newer one cannot overwrite
  // it. Writes land fast enough locally that this is rare, and silently showing
  // a stale list would be exactly the bug this whole layer exists to remove.
  const sequence = useRef(0);
  const mounted = useRef(true);

  const run = useCallback(() => {
    const ticket = ++sequence.current;
    void readRef.current(getStore()).then(
      (value) => {
        if (!mounted.current || ticket !== sequence.current) return;
        setData(value);
        setLoading(false);
      },
      () => {
        if (!mounted.current || ticket !== sequence.current) return;
        // A local read that throws is a bug in the query, not a network
        // failure — there is no offline case to surface here. Stop the
        // spinner and leave whatever was already on screen.
        setLoading(false);
      },
    );
  }, []);

  const channel = tables.join(',');

  useEffect(() => {
    mounted.current = true;
    run();
    const unsubscribe = getStore().subscribe((changed) => {
      if (changed.some((table) => channel.split(',').includes(table))) run();
    });
    return () => {
      mounted.current = false;
      unsubscribe();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channel, run, ...deps]);

  return { data, loading, reload: run };
}

/**
 * {@link useLive} with a fallback, for the common case where "nothing yet" and
 * "genuinely empty" render the same — a list, a count, a map. Screens that need
 * to tell the two apart (an empty state that should not flash before the first
 * sync) use `useLive` and check `loading`.
 */
export function useLiveValue<T>(
  tables: readonly StoreTable[],
  read: (store: LocalStore) => Promise<T>,
  initial: T,
  deps: readonly unknown[] = [],
): T {
  return useLive(tables, read, deps).data ?? initial;
}
