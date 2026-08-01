import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

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
  /** Put a just-created save at the top without waiting for a round trip. */
  prepend: (save: SaveResponse) => void;
}

const SavesContext = createContext<SavesContextValue | null>(null);

/**
 * The feed.
 *
 * Lives in a provider rather than a hook because Capture is its own route: the
 * sheet creates a save and Home has to show it, and the two never share a
 * component tree.
 *
 * **Deliberately does not poll.** A save sits at `processing` until the job
 * runner picks it up, and the job runner does not exist yet (Phase 2) — so
 * polling would spin forever and never observe a transition. Pull-to-refresh
 * covers the gap until there is either a runner or the push notification that
 * the pipeline is meant to send.
 */
export function SavesProvider({ children }: { children: React.ReactNode }) {
  const { session } = useSession();
  const [saves, setSaves] = useState<SaveResponse[]>([]);
  const [status, setStatus] = useState<FeedStatus>('idle');
  const [error, setError] = useState<ApiError | null>(null);
  const [refreshing, setRefreshing] = useState(false);

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

  const prepend = useCallback((save: SaveResponse) => {
    setSaves((prev) => [save, ...prev.filter((s) => s.id !== save.id)]);
    setStatus('ready');
  }, []);

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
