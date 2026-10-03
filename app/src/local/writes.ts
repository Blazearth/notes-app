/**
 * What a screen calls to change something.
 *
 * Every function here does the same two things in the same order — write the new
 * value to the local store, then queue the request — and nothing else. There is
 * no `await` a screen has to hold, no `try` for it to write, and above all **no
 * revert logic**, which is what this file exists to delete.
 *
 * Before L3, each of the app's write sites hand-rolled the same three steps:
 * flip local state, fire the request, and on failure work out what to put back.
 * That shape is wrong in two ways no amount of care at the call site fixes:
 *
 * - **A failed request was simply lost.** Nothing retried it, so a tick made in
 *   a lift never happened, and the app showed a value the server had never
 *   heard of with no way to notice.
 * - **"What to put back" is unanswerable** once a second tap has landed. Several
 *   sites had already given up and re-fetched the whole save instead, which is
 *   both slower and wrong in the offline case the re-fetch cannot complete.
 *
 * So the queue owns delivery, and a write that genuinely cannot succeed is
 * *surfaced* (`useOutbox`) rather than silently rolled back — see
 * `@/local/outbox`'s notes on why `validation`/`notFound`/`forbidden`/`quota`
 * are terminal and `network`/`server` are not.
 *
 * These are all synchronous-looking and return `void`: the store write is
 * queued, not awaited, exactly as `SavesProvider.patch` already was. A caller
 * that wants to know when the *server* agrees is asking the wrong question — the
 * store is the source of truth for interaction.
 */

import type { CreateSaveRequest, LifecycleStatus, SaveResponse, ShoppingListItem } from '@/api/types';
import { getStore } from './index';
import { isLocalId, newLocalId, shoppingListKey } from './outbox';
import { sync } from './sync';

// ------------------------------------------------------------------- saves

/** Swipe-to-favorite / swipe-to-archive. Either field may be omitted. */
export function writeSaveFlags(
  id: string,
  changes: { favorite?: boolean; archived?: boolean },
): void {
  void getStore().patchSave(id, changes);
  void sync.enqueue('setSaveFlags', { id, flags: changes }, id);
}

/**
 * Permanently deletes a save — removes it from the local store immediately
 * and queues the DELETE request. Cannot be undone.
 */
export function writeDeleteSave(id: string): void {
  void getStore().removeSave(id);
  void sync.enqueue('deleteSave', { id }, id);
}

/**
 * Edits a text note's title and body.
 *
 * The optimistic write has to reach `structuredData` as well as `rawCaption`,
 * because that is where the detail screen and every card read a note's text
 * from — writing only what the request carries would leave the editor agreeing
 * with the user and the card still showing the old words.
 *
 * The strongest case in this file for going through the queue rather than
 * firing a request: this is text the user typed, nothing else holds a copy of
 * it, and the editor is dismissed before the request resolves.
 */
export function writeNote(save: SaveResponse, title?: string, body?: string): void {
  void getStore().patchSave(save.id, {
    structuredData: { ...(save.structuredData ?? {}), title, body },
    rawCaption: body,
  });
  void sync.enqueue('updateNote', { id: save.id, title, body }, save.id);
}

export function writeSaveLifecycle(id: string, lifecycleStatus: LifecycleStatus): void {
  void getStore().patchSave(id, { lifecycleStatus });
  void sync.enqueue('setSaveLifecycle', { id, lifecycleStatus }, id);
}

export function writeSaveSpace(id: string, spaceId: string | null): void {
  void getStore().patchSave(id, { spaceId: spaceId ?? undefined });
  void sync.enqueue('setSaveSpace', { id, spaceId }, id);
}

/**
 * The one mechanism behind every knowledge type's object behavior — exercise
 * ticks, checklist items, watch status, reading progress.
 *
 * Takes the whole save rather than just the id because the store's item-state
 * write is per-path and the caller already holds the save; reading it back here
 * would race the write above.
 */
export function writeSaveItemState(
  save: SaveResponse,
  itemPath: string,
  state: Record<string, unknown>,
): void {
  void getStore().putItemState(save.id, itemPath, state);
  void sync.enqueue('setSaveItemState', { id: save.id, itemPath, state }, save.id);
}

/**
 * K2's counterpart, keyed by entity rather than by one save's item path, so it
 * survives the same title appearing in a later save.
 *
 * Every screen showing that entity updates from this one write — the collection
 * list, its section counts, and the same item on a `recommendation_list` save's
 * detail screen all read `entity_states`.
 */
export function writeEntityState(entityKey: string, state: Record<string, unknown>): void {
  void getStore().putEntityStates({ [entityKey]: state });
  void sync.enqueue('setEntityState', { entityKey, state }, entityKey);
}

/**
 * Creates a save, offline-first.
 *
 * The returned save is what the caller should show immediately. Its id is a
 * `local:` placeholder until the POST lands, at which point the store moves the
 * row onto the real id in one write (`reconcileSaveId`) and rewrites any queued
 * entry that referenced the old one.
 *
 * `status: 'processing'` is honest either way: the server returns 202 with
 * exactly that, because the pipeline has not run. A save waiting in the queue and
 * a save waiting on yt-dlp look the same to the user, and both are true.
 */
export function writeCreateSave(body: CreateSaveRequest): SaveResponse {
  const localId = newLocalId();
  const timestamp = new Date().toISOString();
  const save: SaveResponse = {
    id: localId,
    sourceType: body.sourceType,
    sourceUrl: body.sourceUrl,
    spaceId: body.spaceId,
    status: 'processing',
    favorite: false,
    archived: false,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  void getStore().putSaves([save]);
  void sync.enqueue('createSave', { body, localId }, localId);
  return save;
}

/** The Act, on the one type that has one. */
export function writeConvertToShoppingList(saveId: string): void {
  void sync.enqueue('convertToShoppingList', { saveId }, saveId);
}

// ----------------------------------------------------------- shopping list

/**
 * Ticking an item off.
 *
 * The interaction this layer exists for: repeated taps, standing in a shop, on
 * the worst connection the app ever sees. Nothing waits for a round trip and
 * nothing is lost if there is no round trip to be had.
 */
export function writeShoppingItemChecked(itemId: string, checked: boolean): void {
  void getStore().patchShoppingItem(itemId, { checked });
  void sync.enqueue('setShoppingItemChecked', { itemId, checked }, shoppingListKey());
}

/** "I've been shopping" — drops everything ticked, keeps the rest. */
export function writeClearCheckedShoppingItems(items: readonly ShoppingListItem[]): void {
  const removed = items.filter((item) => item.checked).map((item) => item.id);
  if (removed.length === 0) return;
  void getStore().removeShoppingItems(removed);
  // The list's key, shared with every tick on it: the clear has to wait for a
  // tick still in backoff, or the server clears without that item.
  void sync.enqueue('clearCheckedShoppingItems', {}, shoppingListKey());
}

// ------------------------------------------------- S4: a Space's shared list

/**
 * The Space's list is **queued but not cached**, exactly like comments and
 * votes below.
 *
 * The local store holds one shopping list, and adding a second scope to it
 * would mean a `SCHEMA_VERSION` bump — which L5 established must never happen
 * for something the local data can simply re-fetch, because a bump drops the
 * `outbox` and with it writes the server has never seen. So the Space's list is
 * an on-demand read the screen holds itself (the same call `SpaceDetailScreen`
 * already makes for activity and duplicates), and what these two get from the
 * queue is delivery: a tick made in a shop reaches the server whenever there is
 * a network, whether or not the screen still exists.
 *
 * The *item* op is the personal one unchanged. An item id addresses exactly one
 * row on exactly one list, and the server proves access per statement — so
 * there is no Space variant to write, here or on the server.
 */
export function writeSpaceShoppingItemChecked(spaceId: string, itemId: string, checked: boolean): void {
  void sync.enqueue('setShoppingItemChecked', { itemId, checked }, shoppingListKey(spaceId));
}

export function writeClearCheckedSpaceShoppingItems(spaceId: string): void {
  void sync.enqueue('clearCheckedShoppingItems', { spaceId }, shoppingListKey(spaceId));
}

// ------------------------------------------------------------------- S4: pins

/**
 * Pins what somebody chose to put at the top of a Space — a save as the
 * "current program", a collection above the others.
 *
 * An absolute set on `(space, kind, subject)` like every other op here, which
 * is what makes the retry harmless: the server's write is an upsert, so a
 * replayed pin is the same one pin.
 */
export function writePin(
  spaceId: string,
  kind: string,
  subject: string,
  payload: Record<string, unknown> = {},
): string {
  const localId = newLocalId();
  void sync.enqueue('pinInSpace', { spaceId, kind, subject, payload, localId }, pinKey(spaceId, kind, subject));
  return localId;
}

/**
 * Same queue key as the pin, so a pin and an unpin of one subject always reach
 * the server in the order they were tapped. `pinId` may be the `local:` id
 * {@link writePin} returned — the sender resolves it once the pin has landed.
 */
export function writeUnpin(spaceId: string, pinId: string, kind: string, subject: string): void {
  void sync.enqueue('unpinInSpace', { spaceId, pinId }, pinKey(spaceId, kind, subject));
}

function pinKey(spaceId: string, kind: string, subject: string): string {
  return `${spaceId}|${kind}|${subject}`;
}

// ------------------------------------------------------------- discussion

/**
 * Comments and votes are queued but **not** cached.
 *
 * Neither `save_comments` nor `save_votes` is in the delta (see
 * `docs/local-first.md`'s table — comments would need "comments on saves I can
 * see", which is a join across Spaces for a detail-screen read; a vote is only
 * ever seen as part of a score), so there is no local table for these to write
 * to and the calling component keeps its own optimistic copy as it always did.
 * What they gain from the queue is delivery: a comment typed on a train is sent
 * when there is a network, instead of being dropped with the draft.
 */
/** Returns the `local:` id to show the comment under until a reload replaces it. */
export function writeComment(saveId: string, body: string): string {
  const localId = newLocalId();
  void sync.enqueue('addComment', { saveId, body, localId }, saveId);
  return localId;
}

/**
 * A `local:` comment is queued under the save, behind its own `addComment`, so
 * the delete cannot run first; the sender resolves it to the real id. Dropping
 * it locally and sending nothing — what this used to do — still posted it.
 */
export function writeDeleteComment(saveId: string, commentId: string): void {
  void sync.enqueue('deleteComment', { saveId, commentId }, isLocalId(commentId) ? saveId : commentId);
}

export function writeVote(saveId: string, value: 1 | -1 | 0): void {
  void sync.enqueue('setVote', { saveId, value }, saveId);
}

/**
 * S3: a remark about a merged entity rather than about whichever save mentioned
 * it.
 *
 * Queued but not cached, for the same reason as the two above — `entity_comments`
 * is not in the delta (scoping "comments I can see" is a join across Spaces for
 * a detail-screen read), so the screen keeps its own optimistic copy. The queue
 * is what makes a remark typed on a train arrive rather than being lost with the
 * draft.
 *
 * `entityId` is the entity, not the Space: two remarks about Blue Box have to
 * land in the order they were written, while a remark about Blue Box and one
 * about Frieren have nothing to do with each other and must not queue behind
 * one another's backoff.
 */
export function writeEntityComment(spaceId: string, entityKey: string, body: string): string {
  const localId = newLocalId();
  void sync.enqueue('addEntityComment', { spaceId, entityKey, body, localId }, entityKey);
  return localId;
}

/** Same ordering rule as {@link writeDeleteComment}, under the entity's key. */
export function writeDeleteEntityComment(spaceId: string, entityKey: string, commentId: string): void {
  void sync.enqueue(
    'deleteEntityComment',
    { spaceId, commentId },
    isLocalId(commentId) ? entityKey : commentId,
  );
}
