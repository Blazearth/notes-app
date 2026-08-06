import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import type { SaveResponse } from '@/api/types';
import { useSession } from '@/auth/SessionProvider';

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
   * server's echoed response. One shared store means a favorite toggled in
   * the Library is already reflected if the same save is open elsewhere.
   */
  patch: (id: string, changes: Partial<SaveResponse>) => void;
}

const SavesContext = createContext<SavesContextValue | null>(null);

const POLL_INTERVAL = 2000;
const POLL_TIMEOUT = 10 * 60 * 1000; // give up after 10 min

/**
 * The feed.
 *
 * Lives in a provider rather than a hook because Capture is its own route: the
 * sheet creates a save and Home has to show it, and the two never share a
 * component tree.
 *
 * Polls individual saves that are `processing` after upload, stopping when the
 * save reaches `ready` or `failed`. Exponential backoff avoids hammering the
 * API while the pipeline is still running.
 */
export function SavesProvider({ children }: { children: React.ReactNode }) {
  const { session } = useSession();
  const [saves, setSaves] = useState<SaveResponse[]>([]);
  const [status, setStatus] = useState<FeedStatus>('idle');
  const [error, setError] = useState<ApiError | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const pollTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const load = useCallback(async (isRefresh: boolean) => {
    if (isRefresh) setRefreshing(true);
    else setStatus('loading');
    setError(null);

    try {
      const items = await repo.listSaves();
      setSaves(items);
      setStatus('ready');
    } catch (e) {
      setError(
        e instanceof ApiError ? e : new ApiError('server', 'Something went wrong', null),
      );
      setStatus('error');
    } finally {
      if (isRefresh) setRefreshing(false);
    }
  }, []);

  // Cancel all timers on unmount
  useEffect(() => {
    return () => {
      pollTimers.current.forEach((t) => clearTimeout(t));
    };
  }, []);

  // Load on sign-in; clear on sign-out so a second account never sees the
  // first one's rows.
  useEffect(() => {
    if (!session) {
      setSaves([]);
      setStatus('idle');
      setError(null);
      return;
    }
    void load(false);
  }, [session, load]);

  const refresh = useCallback(() => load(true), [load]);

  /** Poll GET /saves/:id every 2s until ready/failed or timeout. */
  const startPolling = useCallback((saveId: string, startedAt: number) => {
    // Already polling this save — don't double-schedule
    if (pollTimers.current.has(saveId)) return;

    const timer = setTimeout(async () => {
      pollTimers.current.delete(saveId);

      if (Date.now() - startedAt > POLL_TIMEOUT) return;

      try {
        const updated = await repo.getSave(saveId);
        setSaves((prev) => prev.map((s) => (s.id === updated.id ? updated : s)));
        if (updated.status === 'processing') {
          startPolling(saveId, startedAt);
        }
      } catch {
        // Network hiccup — retry
        startPolling(saveId, startedAt);
      }
    }, POLL_INTERVAL);

    pollTimers.current.set(saveId, timer);
  }, []);

  const prepend = useCallback((save: SaveResponse) => {
    setSaves((prev) => [save, ...prev.filter((s) => s.id !== save.id)]);
    setStatus('ready');
    if (save.status === 'processing') {
      startPolling(save.id, Date.now());
    }
  }, [startPolling]);

  const patch = useCallback((id: string, changes: Partial<SaveResponse>) => {
    setSaves((prev) => prev.map((s) => (s.id === id ? { ...s, ...changes } : s)));
  }, []);

  const value = useMemo(
    () => ({ saves, status, error, refresh, refreshing, prepend, patch }),
    [saves, status, error, refresh, refreshing, prepend, patch],
  );

  return <SavesContext.Provider value={value}>{children}</SavesContext.Provider>;
}

export function useSaves(): SavesContextValue {
  const ctx = useContext(SavesContext);
  if (!ctx) throw new Error('useSaves must be used inside <SavesProvider>');
  return ctx;
}
