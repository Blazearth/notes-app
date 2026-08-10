/**
 * The outbox — every local write that has not reached the server yet.
 *
 * Before L3, each of the app's ~12 write sites hand-rolled the same three-step
 * dance: flip local state, fire the request, and on failure try to work out what
 * to put back. That is wrong in two ways that no amount of care at the call site
 * fixes. A failed request was simply *lost* — nothing retried it, so a tick made
 * in a lift never happened. And "what to put back" is unanswerable once a second
 * tap has landed, which is why several of those sites gave up and re-fetched
 * instead.
 *
 * So a write now goes to the store and to this queue, and the queue is the only
 * thing that talks to the network about it. The store is what the user sees; the
 * queue is what the server eventually agrees with.
 *
 * **Last-write-wins is not a compromise here, it is the semantics.** Every
 * queued op is an absolute set — `favorite: true`, `state: {...}`,
 * `checked: false` — never a delta, so replaying one is harmless and the newest
 * one always wins. That is what makes retrying safe without a single line of
 * conflict resolution, and it is the reason `docs/local-first.md` rules out CRDTs
 * rather than deferring them.
 *
 * **This file has no runtime imports at all**, and that is deliberate rather
 * than incidental: the ordering, the backoff, the terminal-vs-retryable split
 * and the local-id rewrite are the highest-risk logic in this layer, and keeping
 * them pure is what lets them be executed standalone under node
 * (`docs/testing.md`) instead of only typechecked. The store-facing half lives in
 * `@/local/sync` (queueing and draining) and `@/local/writes` (what each screen
 * calls).
 */

import type { ApiErrorKind } from '@/api/client';
import type { CreateSaveRequest, LifecycleStatus } from '@/api/types';

// ---------------------------------------------------------------------- types

/**
 * The ops a queued write can be. Each name is a `Repository` method, and the
 * payload is its arguments — so a queued write is described in the app's own
 * vocabulary rather than as a serialised HTTP request, which would pin the queue
 * to a URL shape that can change under it.
 *
 * Deliberately **not** here: `createSpace` and `acceptInvite`. Both hand back a
 * Space the user is then navigated into, so there is nothing useful to do with
 * them offline, and both are the writes L5's `V16__idempotency.sql` exists for —
 * queueing them before that migration lands would be the one way to create
 * duplicate Spaces. `mergeDuplicate`/`dismissDuplicate` are out for a related
 * reason: the suggestions they act on are not cached, so the screen has nothing
 * to show optimistically.
 */
export type OutboxOp =
  | 'createSave'
  | 'updateNote'
  | 'setSaveFlags'
  | 'setSaveLifecycle'
  | 'setSaveSpace'
  | 'deleteSave'
  | 'setSaveItemState'
  | 'setEntityState'
  | 'setShoppingItemChecked'
  | 'clearCheckedShoppingItems'
  | 'convertToShoppingList'
  | 'addComment'
  | 'deleteComment'
  | 'setVote';

/** The payload shape per op, so the drain's dispatch is exhaustively typed. */
export interface OutboxPayloads {
  createSave: { body: CreateSaveRequest; localId: string };
  /**
   * A text note's title and body. An absolute set like every other op here —
   * `PATCH /v1/saves/{id}/note` replaces both fields — which is what makes a
   * retry harmless. It matters more than most: this is text the user typed and
   * nothing else holds a copy of, so a lost request loses the writing.
   */
  updateNote: { id: string; title?: string; body?: string };
  setSaveFlags: { id: string; flags: { favorite?: boolean; archived?: boolean } };
  setSaveLifecycle: { id: string; lifecycleStatus: LifecycleStatus };
  setSaveSpace: { id: string; spaceId: string | null };
  deleteSave: { id: string };
  deleteSave: { id: string };
  setSaveItemState: { id: string; itemPath: string; state: Record<string, unknown> };
  setEntityState: { entityKey: string; state: Record<string, unknown> };
  setShoppingItemChecked: { itemId: string; checked: boolean };
  clearCheckedShoppingItems: Record<string, never>;
  convertToShoppingList: { saveId: string };
  addComment: { saveId: string; body: string };
  deleteComment: { saveId: string; commentId: string };
  setVote: { saveId: string; value: 1 | -1 | 0 };
}

export type OutboxStatus = 'pending' | 'failed';

export interface OutboxEntry<O extends OutboxOp = OutboxOp> {
  /** Monotonic, and the queue's order. Assigned by the store. */
  id: number;
  op: O;
  payload: OutboxPayloads[O];
  /** Generated once at enqueue — see {@link enqueue}. */
  idempotencyKey: string;
  /**
   * What this write is about: a save id, an entity key, a shopping item id. Two
   * entries sharing one are kept in order relative to each other; entries about
   * different things never block one another.
   */
  entityId: string | null;
  createdAt: string;
  attempts: number;
  /** ISO. Null means "eligible now". */
  nextAttemptAt: string | null;
  lastError: string | null;
  status: OutboxStatus;
}

/** What a caller supplies; the store fills in the rest. */
export interface OutboxDraft<O extends OutboxOp = OutboxOp> {
  op: O;
  payload: OutboxPayloads[O];
  idempotencyKey: string;
  entityId: string | null;
  createdAt: string;
}

// ------------------------------------------------------------ pure: dispositions

/**
 * What a failure means for the entry that caused it.
 *
 * - `retry` — the request never got a verdict (`network`) or the server could
 *   not give one (`server`). Back off and try again.
 * - `fail`  — the server *did* decide, and it will decide the same way forever.
 *   Retrying a 400 or a 403 in a loop is how a queue turns one bad write into a
 *   permanently stuck app, so these are surfaced instead.
 * - `pause` — the token expired. Nothing in the queue can succeed until the
 *   session refreshes, and spending each entry's retry budget discovering that
 *   would empty the budget for reasons that have nothing to do with the entries.
 *
 * `quota` is a `fail` and that is a judgement call worth stating: it is not
 * permanent (a month rolls over, or the user upgrades), but it is not fixed by
 * waiting seconds either, and a paywall the user has to act on is exactly the
 * kind of thing that must be *shown* rather than retried behind their back.
 */
export type Disposition = 'retry' | 'fail' | 'pause';

export function dispositionFor(kind: ApiErrorKind): Disposition {
  switch (kind) {
    case 'network':
    case 'server':
      return 'retry';
    case 'unauthorized':
      return 'pause';
    case 'validation':
    case 'notFound':
    case 'forbidden':
    case 'quota':
      return 'fail';
  }
}

// ---------------------------------------------------------------- pure: backoff

export const RETRY_BASE_MS = 1_000;
/**
 * Five minutes. Long enough that a queue waiting out a real outage costs
 * nothing, short enough that the app feels responsive when connectivity returns
 * — which it does not have to wait for anyway, since regained connectivity
 * triggers a drain directly rather than being discovered by a timer.
 */
export const RETRY_CAP_MS = 5 * 60 * 1_000;

/** @param attempts how many attempts have now been made, including the one that just failed */
export function backoffMs(attempts: number): number {
  const exponent = Math.max(0, attempts - 1);
  // 2 ** 30 * 1000 overflows nothing in JS but the cap makes the growth moot
  // long before that; clamping the exponent keeps the arithmetic honest anyway.
  const grown = RETRY_BASE_MS * 2 ** Math.min(exponent, 20);
  return Math.min(grown, RETRY_CAP_MS);
}

export function nextAttemptAt(attempts: number, nowMs: number): string {
  return new Date(nowMs + backoffMs(attempts)).toISOString();
}

// ------------------------------------------------------------- pure: selection

/**
 * The next entry to send, or null if there is nothing due.
 *
 * Two rules, and the second is the one worth reading twice:
 *
 * - **A `failed` entry is skipped, never a head-of-line block.** One rejected
 *   write must not freeze every write behind it.
 * - **An entry not yet due blocks later entries about the *same thing*, and
 *   nothing else.** Two flag changes to one save have to land in the order the
 *   user made them or the save ends up in the wrong state; a flag change to one
 *   save and a tick on another are unrelated, and making the second wait for the
 *   first's backoff would turn one flaky write into a stalled app.
 */
export function selectNext(entries: readonly OutboxEntry[], nowMs: number): OutboxEntry | null {
  const blocked = new Set<string>();
  for (const entry of entries) {
    if (entry.status === 'failed') continue;
    if (entry.entityId !== null && blocked.has(entry.entityId)) continue;
    if (entry.nextAttemptAt !== null && Date.parse(entry.nextAttemptAt) > nowMs) {
      if (entry.entityId !== null) blocked.add(entry.entityId);
      continue;
    }
    return entry;
  }
  return null;
}

/** When the earliest not-yet-due entry becomes due, so a timer can be set once. */
export function nextWakeMs(entries: readonly OutboxEntry[], nowMs: number): number | null {
  let soonest: number | null = null;
  for (const entry of entries) {
    if (entry.status === 'failed' || entry.nextAttemptAt === null) continue;
    const due = Date.parse(entry.nextAttemptAt);
    if (due <= nowMs) continue;
    if (soonest === null || due < soonest) soonest = due;
  }
  return soonest === null ? null : soonest - nowMs;
}

// -------------------------------------------------------------- pure: local ids

/**
 * A save created offline has no server id, so it gets one of these until the
 * POST lands. The prefix is what every other part of the store checks against —
 * notably `putSaves({ replaceAll: true })`, which must not reap a row the server
 * has never heard of.
 */
export const LOCAL_ID_PREFIX = 'local:';

export function isLocalId(id: string): boolean {
  return id.startsWith(LOCAL_ID_PREFIX);
}

export function newLocalId(): string {
  return LOCAL_ID_PREFIX + randomId();
}

/**
 * Points one queued entry at a save's real id.
 *
 * Both halves matter. `entityId` is what {@link selectNext} orders by, so a
 * dependent op left pointing at the local id would keep queueing behind an entry
 * that no longer exists. The payload is what actually gets sent, and a `PATCH
 * /v1/saves/local:abc/flags` is a 404 — which {@link dispositionFor} would
 * (correctly, given what it was told) mark as permanently failed, quietly losing
 * the write.
 *
 * The rewrite is textual over the serialised payload rather than per-op, so an
 * op added later cannot forget to participate. That is safe because a local id is
 * a uuid behind a prefix that appears in no other value the app stores.
 */
export function rewriteLocalId<O extends OutboxOp>(
  entry: OutboxEntry<O>,
  localId: string,
  realId: string,
): OutboxEntry<O> {
  const serialised = JSON.stringify(entry.payload);
  if (!serialised.includes(localId) && entry.entityId !== localId) return entry;
  return {
    ...entry,
    entityId: entry.entityId === localId ? realId : entry.entityId,
    payload: JSON.parse(serialised.split(localId).join(realId)) as OutboxPayloads[O],
  };
}

/**
 * A uuid v4, from `crypto.getRandomValues` where it exists.
 *
 * `crypto.randomUUID` is deliberately not used: it is absent on Hermes and,
 * on web, is only exposed in secure contexts — and the fallback below has to be
 * good enough anyway, because what these ids need is uniqueness, not
 * unpredictability. An idempotency key is scoped to one user's own request.
 */
export function randomId(): string {
  const bytes = new Uint8Array(16);
  const webCrypto = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => void } }).crypto;
  if (webCrypto?.getRandomValues) {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0'));
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join(''),
  ].join('-');
}

/**
 * A short, human-readable label for a queued write — what a "couldn't be saved"
 * strip shows instead of an op name.
 *
 * Deliberately not a `Record<OutboxOp, string>` lookup at the call site: naming
 * the op *here*, beside the payload types, is what keeps a new op from shipping
 * with a blank label in the one UI that reports failures.
 */
export function describeOp(op: OutboxOp): string {
  switch (op) {
    case 'createSave':
      return 'Saving a link';
    case 'updateNote':
      return 'Editing a note';
    case 'setSaveFlags':
      return 'Favourite or archive';
    case 'setSaveLifecycle':
      return 'Progress';
    case 'setSaveSpace':
      return 'Moving a save into a Space';
    case 'deleteSave':
      return 'Deleting a save';
    case 'setSaveItemState':
    case 'setEntityState':
      return 'Ticking something off';
    case 'setShoppingItemChecked':
    case 'clearCheckedShoppingItems':
      return 'Shopping list';
    case 'convertToShoppingList':
      return 'Adding a recipe to your list';
    case 'addComment':
    case 'deleteComment':
      return 'A comment';
    case 'setVote':
      return 'A vote';
  }
}
