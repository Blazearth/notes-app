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
