/**
 * The sync engine's status, as a hook.
 *
 * Screens read *data* from the store and *liveness* from here. Keeping the two
 * apart is what lets a screen paint cached content immediately and still say
 * "refreshing" honestly, instead of collapsing both into one `loading` boolean
 * that has to be false for the content to show.
 */

import { useSyncExternalStore } from 'react';

import type { ApiError } from '@/api/client';
import { useLiveValue } from './useLive';
import type { OutboxEntry } from './outbox';
import { sync, type SyncStatus, type SyncTask } from './sync';

export function useSyncStatus(): SyncStatus {
  return useSyncExternalStore(sync.subscribe, sync.getStatus, sync.getStatus);
}

export interface TaskStatus {
  /** In flight right now. */
  running: boolean;
  /** Has landed at least once in this install — not just this session. */
  completed: boolean;
  /** Last failure, cleared by the next success. */
  error: ApiError | null;
  /**
   * The one derived answer nearly every screen wants: nothing local, nothing
   * ever fetched, and no error to explain it — i.e. show a spinner rather than
   * an empty state. Callers pass whether they hold data.
   */
  firstLoad: boolean;
}

export function useTaskStatus(task: SyncTask, hasLocalData = false): TaskStatus {
  const status = useSyncStatus();
  const completed = status.completed[task] === true;
  const error = status.errors[task] ?? null;
  return {
    running: status.running.includes(task),
    completed,
    error,
    firstLoad: !hasLocalData && !completed && error == null,
  };
}

/** Stable identity for the (overwhelmingly common) empty queue. */
const NO_ENTRIES: OutboxEntry[] = [];

export interface OutboxView {
  /** On their way, or waiting out a backoff. Usually empty. */
  pending: OutboxEntry[];
  /**
   * The server said no, and will say no again — a 400, a 403, a 404, or a cap
   * the user has to act on.
   *
   * This list is the whole reason `@/local/writes` has no revert logic. A
   * terminal failure is not rolled back behind the user's back, because rolling
   * back is indistinguishable from "your change never happened" and the app has
   * already shown them that it did. It is shown, with the two things they can
   * actually do about it.
   */
  failed: OutboxEntry[];
  /** Holding until the session refreshes. Not a failure — nothing was rejected. */
  paused: boolean;
  /** Clears the terminal status and tries again, keeping the idempotency key. */
  retry: (id: number) => void;
  /** Drops the write **and** re-reads the server's truth for what it was about. */
  discard: (id: number) => void;
}

/**
 * The queue, for the one screen that reports on it.
 *
 * A hook rather than a subscription on the sync engine's status, because the
 * outbox lives in the store: `useLive` already re-runs this when the drain writes
 * to it, so there is no second notification mechanism to keep in step.
 */
export function useOutbox(): OutboxView {
  const entries = useLiveValue(['outbox'], (store) => store.readOutbox(), NO_ENTRIES);
  const status = useSyncStatus();
  return {
    pending: entries.filter((entry) => entry.status === 'pending'),
    failed: entries.filter((entry) => entry.status === 'failed'),
    paused: status.outboxPaused,
    retry: (id) => void sync.retryOutbox(id),
    discard: (id) => void sync.discardOutbox(id),
  };
}
