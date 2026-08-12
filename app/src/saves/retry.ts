import type { CreateSaveRequest, SaveResponse } from '@/api/types';
import { isLocalId } from '@/local/outbox';
import { writeCreateSave, writeDeleteSave } from '@/local/writes';

/**
 * Retrying a failed save, client-only.
 *
 * There is no backend endpoint to re-run the pipeline on an existing failed
 * row (see CLAUDE.md) — a retry is a brand-new save from the same source,
 * going through the ordinary create-and-classify flow like any other. This
 * module owns the two things that make that safe to expose as a button:
 * not creating a second live save for a source that already succeeded, and
 * not letting a second tap on the same row start a second attempt.
 */

interface RetryLink {
  /** The id `writeCreateSave` handed back — a `local:` placeholder until the
   * outbox's create succeeds. */
  targetId: string;
  sourceType: SaveResponse['sourceType'];
  sourceUrl?: string;
  rawCaption?: string;
  startedAt: number;
}

/**
 * originalFailedId -> the retry attempt started for it.
 *
 * Module-scope rather than component state: `SaveCard` renders the same
 * failed save from different trees on Home and Library, and both need to
 * agree on whether a retry is already in flight. Lost on an app reload —
 * acceptable, since nothing here is unrecoverable: the recreated save is a
 * real row in the store either way, and a reload just means the original
 * failed row stops tracking it and needs a fresh tap.
 */
const retryLinks = new Map<string, RetryLink>();

/**
 * Resolves the live save behind a retry, or `undefined` if none is running.
 *
 * `writeCreateSave` returns a `local:` id immediately, and the outbox renames
 * that row onto the server's real id the moment the create succeeds
 * (`reconcileSaveId`) — nothing outside the store can look up what a local id
 * became, so once the tracked id stops matching anything, this falls back to
 * identifying the same row by what doesn't change under a rename: its source
 * and when the retry started. Excludes `originalId` itself, and anything
 * older than the retry, so it can't accidentally latch onto an unrelated save
 * that happens to share a source.
 */
export function retryTarget(originalId: string, saves: readonly SaveResponse[]): SaveResponse | undefined {
  const link = retryLinks.get(originalId);
  if (!link) return undefined;

  const byId = saves.find((s) => s.id === link.targetId);
  if (byId) return byId;

  const isCandidate = (s: SaveResponse) =>
    s.id !== originalId && s.id !== link.targetId && new Date(s.createdAt).getTime() >= link.startedAt;

  if (link.sourceUrl) {
    return saves.find((s) => isCandidate(s) && s.sourceUrl === link.sourceUrl);
  }
  if (link.rawCaption) {
    return saves.find((s) => isCandidate(s) && s.rawCaption === link.rawCaption);
  }
  return undefined;
}

export function isRetrying(originalId: string): boolean {
  return retryLinks.has(originalId);
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

/**
 * Starts a retry: recreates the save from its own source and links the
 * attempt so the failed row can watch it. Returns `true` if an attempt was
 * started, or `false` if nothing was — either a retry is already running for
 * this row, a live (non-failed) save for the same source already exists (the
 * failed row is dropped instead, per the no-duplicates rule), or the save
 * doesn't carry enough to retry at all.
 */
export function startRetry(save: SaveResponse, saves: readonly SaveResponse[]): boolean {
  if (retryLinks.has(save.id)) return false;
  if (!canRetry(save)) return false;

  if (save.sourceType === 'url') {
    const key = sourceKey(save);
    const existing = saves.find((s) => s.id !== save.id && s.status !== 'failed' && sourceKey(s) === key);
    if (existing) {
      writeDeleteSave(save.id);
      return false;
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
  retryLinks.set(save.id, {
    targetId: created.id,
    sourceType: save.sourceType,
    sourceUrl: save.sourceUrl,
    rawCaption: save.sourceType === 'text' ? save.rawCaption : undefined,
    startedAt: Date.now(),
  });
  return true;
}

/**
 * Called once the linked retry attempt has resolved (reached `ready` or
 * `failed`). On success the now-redundant original is dropped in favor of the
 * new, live save. On failure the new attempt is dropped instead, so the user
 * is left with exactly one failed row — the original — to retry again,
 * rather than a growing chain of dead attempts.
 */
export function resolveRetry(originalId: string, target: SaveResponse, succeeded: boolean): void {
  retryLinks.delete(originalId);
  if (succeeded) {
    writeDeleteSave(originalId);
  } else if (!isLocalId(target.id)) {
    writeDeleteSave(target.id);
  }
}
