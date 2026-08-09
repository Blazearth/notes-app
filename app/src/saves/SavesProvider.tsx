import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef } from 'react';

import type { ApiError } from '@/api/client';
import type { SaveResponse } from '@/api/types';
import { getStore, useLive } from '@/local';
import { useStoreReady } from '@/local/SyncProvider';
import { sync } from '@/local/sync';
import { useTaskStatus } from '@/local/useSync';

export type FeedStatus = 'idle' | 'loading' | 'ready' | 'error';

interface SavesContextValue {
  saves: SaveResponse[];
  status: FeedStatus;
  error: ApiError | null;
  /** Manual reload. `refreshing` distinguishes it from the first load. */
  refresh: () => Promise<void>;
  refreshing: boolean;
  /** Put a just-created save at the top and start polling until it is ready. */
  prepend: (save: SaveResponse) => void;
  /**
   * Merges a partial update into one save by id — the Library's swipe
   * actions use this for an optimistic flip, then call again with the
   * server's echoed response. The write lands in the local store, so a
   * favorite toggled in the Library is already reflected anywhere else the
   * same save renders, whether or not that screen went through this provider.
   */
  patch: (id: string, changes: Partial<SaveResponse>) => void;
}

const SavesContext = createContext<SavesContextValue | null>(null);

const POLL_INTERVAL = 2000;
const POLL_TIMEOUT = 10 * 60 * 1000; // give up after 10 min

/** Stable identity, so a store read that returns nothing does not re-render. */
const EMPTY: SaveResponse[] = [];

/**
 * The feed — now a *view of the local store*, not a fetch.
 *
 * It used to `await repo.listSaves()` on every sign-in and show a spinner while
 * it did. It now reads whatever the store already holds (instantly, on the
 * first frame, offline) and re-renders when `@/local/sync` writes a fresher
 * copy behind it. A cold start with a warm store paints cards with no spinner
 * at all; a genuinely first run is the only case that still shows one.
 *
 * Still a provider rather than a hook, and still for the original reason:
 * Capture is its own route, so the sheet creating a save and Home showing it
 * never share a component tree. What has changed is that the shared thing is a
 * subscription rather than a copy of the list.
 *
 * It holds the **whole** library, not page 0. That is what lets groups,
 * collections and the Continue rail be derived locally instead of fetched —
 * see `@/groups/tree` and `docs/local-first.md`.
 */
export function SavesProvider({ children }: { children: React.ReactNode }) {
  const storeReady = useStoreReady();
  const pollTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  // `includeArchived` because the Library computes its archived count and its
  // `Archived` chip from this same list — the filtering is a presentation
  // decision, and pushing it down here would make that chip impossible.
  const { data, loading } = useLive(
    ['saves', 'item_states'],
    (store) => store.readFeed({ includeArchived: true }),
    [storeReady],
  );
  const saves = data ?? EMPTY;

  const task = useTaskStatus('saves', saves.length > 0);

  // Cancel all timers on unmount
  useEffect(() => {
    return () => {
      pollTimers.current.forEach((t) => clearTimeout(t));
    };
  }, []);

  const refresh = useCallback(() => sync.refreshAll(), []);

  /** Poll `GET /saves/:id` every 2s until ready/failed or timeout. */
  const startPolling = useCallback((saveId: string, startedAt: number) => {
    // Already polling this save — don't double-schedule
    if (pollTimers.current.has(saveId)) return;

    const timer = setTimeout(async () => {
      pollTimers.current.delete(saveId);

      if (Date.now() - startedAt > POLL_TIMEOUT) return;

      // `pullSave` writes into the store, so the feed updates itself — nothing
      // here has to hold or hand back the result.
      const updated = await sync.pullSave(saveId);
      if (updated == null || updated.status === 'processing') {
        startPolling(saveId, startedAt);
      }
    }, POLL_INTERVAL);

    pollTimers.current.set(saveId, timer);
  }, []);

  const prepend = useCallback(
    (save: SaveResponse) => {
      // No local ordering to maintain: the store sorts by `createdAt` and a
      // just-created save is the newest thing in it by definition.
      void getStore().putSaves([save]);
      if (save.status === 'processing') {
        startPolling(save.id, Date.now());
      }
    },
    [startPolling],
  );

  const patch = useCallback((id: string, changes: Partial<SaveResponse>) => {
    void getStore().patchSave(id, changes);
  }, []);

  const status: FeedStatus = useMemo(() => {
    if (!storeReady) return 'idle';
    if (saves.length > 0) return 'ready';
    // An error only shows when there is nothing cached to show instead. With
    // data on hand, a failed refresh is a background event, not a screen state.
    if (task.error) return 'error';
    if (loading || task.firstLoad) return 'loading';
    return 'ready';
  }, [storeReady, saves.length, task.error, task.firstLoad, loading]);

  const value = useMemo(
    () => ({
      saves,
      status,
      error: task.error,
      refresh,
      refreshing: task.running,
      prepend,
      patch,
    }),
    [saves, status, task.error, task.running, refresh, prepend, patch],
  );

  return <SavesContext.Provider value={value}>{children}</SavesContext.Provider>;
}

export function useSaves(): SavesContextValue {
  const ctx = useContext(SavesContext);
  if (!ctx) throw new Error('useSaves must be used inside <SavesProvider>');
  return ctx;
}
