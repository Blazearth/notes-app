import { useSyncExternalStore } from 'react';

import type { CreateSaveRequest, SaveResponse } from '@/api/types';
import { isLocalId } from '@/local/outbox';
import { sync } from '@/local/sync';
import { writeCreateSave, writeDeleteSave } from '@/local/writes';

/**
 * Retrying a failed save, client-only.
 *
 * There is no backend endpoint to re-run the pipeline on an existing failed
 * row (see CLAUDE.md) — a retry is a brand-new save from the same source,
 * going through the ordinary create-and-classify flow like any other. This
 * module owns the things that make that safe to expose as a button: not
 * creating a second live save for a source that already succeeded, not letting
 * a second tap start a second attempt, and settling the attempt exactly once.
 *
 * **One state, shared, settled in one place.** The per-row state this used to
 * hold went wrong three ways: Home and Library (mounted side by side) disagreed
 * about whether a row was retrying; a row scrolled out of Home's top slice took
 * the only watcher with it, so the attempt never settled; and a FlashList cell
 * recycled onto another failed save carried its spinner along. Rows now *read*
 * {@link useIsRetrying}, and {@link settleRetries} runs from `SavesProvider`,
 * which is mounted for the app's whole life.
 */

interface RetryLink {
  /** The id `writeCreateSave` handed back — a `local:` placeholder until the
   * outbox's create succeeds; {@link liveId} follows it to the real one. */
  targetId: string;
  /** Phone clock only, never compared with a server timestamp. */
  startedAt: number;
}

/**
 * originalFailedId -> the retry attempt started for it. Lost on an app reload —
 * acceptable: the recreated save is a real row either way, and a reload only
 * means the original failed row needs a fresh tap.
 */
const retryLinks = new Map<string, RetryLink>();

/**
 * originalId -> the save that replaced it, for a screen still open on the
 * original when it was deleted (a successful retry, or a duplicate found).
 */
const successors = new Map<string, string>();

const listeners = new Set<() => void>();
let version = 0;

function changed() {
  version += 1;
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Whether a retry is running for this failed save — the same answer on every surface. */
export function useIsRetrying(originalId: string): boolean {
  useSyncExternalStore(subscribe, () => version, () => version);
  return retryLinks.has(originalId);
}

/** A `local:` id once its create has landed, else the id itself. */
function liveId(id: string): string {
  return isLocalId(id) ? (sync.realSaveId(id) ?? id) : id;
}

/**
 * Where a save went, if it was replaced or moved onto a real id — followed
 * through every hop (original → retry's `local:` id → its real id). `undefined`
 * if it simply does not exist.
 */
export function successorOf(id: string): string | undefined {
  let current = id;
  for (let hop = 0; hop < 4; hop += 1) {
    const next = successors.get(current) ?? (isLocalId(current) ? sync.realSaveId(current) : undefined);
    if (!next) break;
    current = next;
  }
  return current === id ? undefined : current;
}

/** Resolves the live save behind a retry, or `undefined` if none is running or it is not stored yet. */
export function retryTarget(originalId: string, saves: readonly SaveResponse[]): SaveResponse | undefined {
  const link = retryLinks.get(originalId);
  if (!link) return undefined;
  const id = liveId(link.targetId);
  return saves.find((s) => s.id === id);
}

function sourceKey(save: SaveResponse): string {
  return `${save.sourceType}:${(save.sourceUrl ?? '').trim().toLowerCase()}`;
}

/** Only these carry enough on the save itself to recreate the request. An
 * image/PDF/audio save's original bytes were never cached client-side, so
 * offering a Retry that can't actually resend them would be worse than not
 * offering one. */
export function canRetry(save: SaveResponse): boolean {
  if (save.sourceType === 'url') return !!save.sourceUrl;
  if (save.sourceType === 'text') return !!save.rawCaption;
  return false;
}

export type RetryStart =
  | { kind: 'started' }
  /** A live save for the same source already exists; the failed row was dropped in its favour. */
  | { kind: 'duplicate'; existingId: string }
  /** Already running, or nothing on the save to retry with. */
  | { kind: 'none' };

/**
 * Starts a retry: recreates the save from its own source and links the
 * attempt. If a live (non-failed) save for the same URL already exists, the
 * failed row is deleted instead, per the no-duplicates rule — and the caller is
 * told which save to show, rather than being left on a deleted one.
 */
export function startRetry(save: SaveResponse, saves: readonly SaveResponse[]): RetryStart {
  if (retryLinks.has(save.id)) return { kind: 'none' };
  if (!canRetry(save)) return { kind: 'none' };

  if (save.sourceType === 'url') {
    const key = sourceKey(save);
    const existing = saves.find((s) => s.id !== save.id && s.status !== 'failed' && sourceKey(s) === key);
    if (existing) {
      successors.set(save.id, existing.id);
      writeDeleteSave(save.id);
      return { kind: 'duplicate', existingId: existing.id };
    }
  }

  const body: CreateSaveRequest =
    save.sourceType === 'url'
      ? { sourceType: 'url', sourceUrl: save.sourceUrl, spaceId: save.spaceId }
      : {
          sourceType: 'text',
          text: save.rawCaption,
          title: typeof save.structuredData?.title === 'string' ? save.structuredData.title : undefined,
          spaceId: save.spaceId,
        };

  const created = writeCreateSave(body);
  retryLinks.set(save.id, { targetId: created.id, startedAt: Date.now() });
  changed();
  return { kind: 'started' };
}

/**
 * Settles every running retry against the current library — called by
 * `SavesProvider` whenever the feed changes, so it runs whatever is mounted.
 *
 * - Target `ready`: the now-redundant original is dropped in favour of it.
 * - Target `failed`, or its create was rejected outright: the attempt is
 *   dropped, leaving exactly one failed row (the original) to retry again,
 *   rather than a growing chain of dead attempts.
 * - Target gone (its failed create was discarded in Settings): the link is
 *   cleared so the original's Retry works again. A grace period covers the
 *   moment between starting a retry and its row landing in the store.
 */
export function settleRetries(
  saves: readonly SaveResponse[],
  /** `local:` id -> the failed `createSave` outbox entry that was to create it. */
  failedCreates: ReadonlyMap<string, number>,
): void {
  let any = false;
  for (const [originalId, link] of [...retryLinks]) {
    const failedEntry = failedCreates.get(link.targetId);
    if (failedEntry !== undefined) {
      retryLinks.delete(originalId);
      // Discarding removes the `local:` row too — otherwise it would sit at
      // "Processing…" next to the original, a save that will never exist.
      void sync.discardOutbox(failedEntry);
      any = true;
      continue;
    }
    const target = retryTarget(originalId, saves);
    if (!target) {
      if (Date.now() - link.startedAt > 10_000) {
        retryLinks.delete(originalId);
        any = true;
      }
      continue;
    }
    if (target.status === 'ready') {
      retryLinks.delete(originalId);
      successors.set(originalId, target.id);
      writeDeleteSave(originalId);
      any = true;
    } else if (target.status === 'failed') {
      retryLinks.delete(originalId);
      if (!isLocalId(target.id)) writeDeleteSave(target.id);
      any = true;
    }
  }
  if (any) changed();
}
