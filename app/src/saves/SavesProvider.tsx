import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef } from 'react';

import type { ApiError } from '@/api/client';
import type { SaveResponse } from '@/api/types';
import { useSession } from '@/auth/SessionProvider';
import { getStore, useLive } from '@/local';
import { isLocalId } from '@/local/outbox';
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
  /**
   * Merges a partial update into one save by id.
   *
   * Kept for reads-shaped-as-writes — a screen correcting its own cached copy of
   * something the server just told it. **Not** the way to change a save: that is
   * `@/local/writes`, which also queues the request. A `patch` alone is a change
   * the server will never hear about.
   */
  patch: (id: string, changes: Partial<SaveResponse>) => void;
}

const SavesContext = createContext<SavesContextValue | null>(null);

const POLL_INTERVAL = 2000;
const POLL_TIMEOUT = 10 * 60 * 1000; // give up after 10 min

/** Stable identity, so a store read that returns nothing does not re-render. */
const EMPTY: SaveResponse[] = [];

/**
 * The feed — a *view of the local store*, not a fetch.
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
  const { session } = useSession();
  // The logged-in user's UUID — used to exclude space-mates' saves from the
  // personal feed. Their saves are stored locally so SpaceDetailScreen can
  // read them offline, but Library and Home should only show the caller's own.
  const myUserId = session?.user.id;

  // `includeArchived` because the Library computes its archived count and its
  // `Archived` chip from this same list — the filtering is a presentation
  // decision, and pushing it down here would make that chip impossible.
  const { data, loading } = useLive(
    ['saves', 'item_states'],
    (store) => store.readFeed({ includeArchived: true, ownedByUserId: myUserId }),
    [storeReady, myUserId],
  );
  const saves = data ?? EMPTY;

  // `delta`, not `saves`: `GET /v1/sync` is what fills the feed now, and the
  // paged `GET /v1/saves` walk only runs on pull-to-refresh. Watching the wrong
  // task here would leave a genuinely-empty first run showing a spinner forever.
  const task = useTaskStatus('delta', saves.length > 0);

  /**
   * Polls whatever is still `processing`, driven by the feed rather than by
   * whoever created the save.
   *
   * That difference matters more than it looks. The old version started a timer
   * inside `prepend`, so only the screen that created a save ever watched it —
   * a save that was still processing when the app was closed sat at
   * "Processing…" until something else refetched it, and a save made on another
   * device never advanced at all. Reading the condition off the store instead
   * covers every case with less code.
   *
   * `local:` saves are excluded: they exist nowhere but here, so there is
   * nothing to poll. They become pollable the moment the outbox reconciles them
   * onto a real id, which lands in this same store and re-runs this effect.
   */
  const startedAt = useRef<Map<string, number>>(new Map());
  const processing = saves
    .filter((save) => save.status === 'processing' && !isLocalId(save.id))
    .map((save) => save.id)
    .join(',');

  useEffect(() => {
    if (!processing) return;
    const ids = processing.split(',');
    const now = Date.now();
    for (const id of ids) if (!startedAt.current.has(id)) startedAt.current.set(id, now);

    const timer = setTimeout(() => {
      for (const id of ids) {
        if (now - (startedAt.current.get(id) ?? now) > POLL_TIMEOUT) continue;
        // `pullSave` writes into the store, so the feed updates itself — nothing
        // here has to hold or hand back the result. When the save reaches
        // `ready` it drops out of `processing` and this effect stops.
        void sync.pullSave(id);
      }
    }, POLL_INTERVAL);

    return () => clearTimeout(timer);
  }, [processing]);

  const refresh = useCallback(() => sync.refreshAll(), []);

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
      patch,
    }),
    [saves, status, task.error, task.running, refresh, patch],
  );

  return <SavesContext.Provider value={value}>{children}</SavesContext.Provider>;
}

export function useSaves(): SavesContextValue {
  const ctx = useContext(SavesContext);
  if (!ctx) throw new Error('useSaves must be used inside <SavesProvider>');
  return ctx;
}
