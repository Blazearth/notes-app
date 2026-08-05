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
}

const SavesContext = createContext<SavesContextValue | null>(null);

// Poll schedule: 3s → 6s → 12s → 24s → 48s → 60s steady until ready/failed/timeout
const POLL_INTERVALS = [3000, 6000, 12000, 24000, 48000];
const POLL_STEADY = 60000;
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

  /** Replace a single save in the array by id. */
  const patchSave = useCallback((updated: SaveResponse) => {
    setSaves((prev) => prev.map((s) => (s.id === updated.id ? updated : s)));
  }, []);

  /** Poll GET /saves/:id with exponential backoff until ready/failed or timeout. */
  const startPolling = useCallback((saveId: string, startedAt: number, step: number) => {
    if (pollTimers.current.has(saveId)) return;

    const delay = step < POLL_INTERVALS.length ? POLL_INTERVALS[step]! : POLL_STEADY;

    const timer = setTimeout(async () => {
      pollTimers.current.delete(saveId);

      if (Date.now() - startedAt > POLL_TIMEOUT) return;

      try {
        const updated = await repo.getSave(saveId);
        patchSave(updated);
        if (updated.status === 'processing') {
          startPolling(saveId, startedAt, step + 1);
        }
      } catch {
        // Network hiccup — retry at next interval
        startPolling(saveId, startedAt, step + 1);
      }
    }, delay);

    pollTimers.current.set(saveId, timer);
  }, [patchSave]);

  const prepend = useCallback((save: SaveResponse) => {
    setSaves((prev) => [save, ...prev.filter((s) => s.id !== save.id)]);
    setStatus('ready');
    if (save.status === 'processing') {
      startPolling(save.id, Date.now(), 0);
    }
  }, [startPolling]);

  const value = useMemo(
    () => ({ saves, status, error, refresh, refreshing, prepend }),
    [saves, status, error, refresh, refreshing, prepend],
  );

  return <SavesContext.Provider value={value}>{children}</SavesContext.Provider>;
}

export function useSaves(): SavesContextValue {
  const ctx = useContext(SavesContext);
  if (!ctx) throw new Error('useSaves must be used inside <SavesProvider>');
  return ctx;
}
