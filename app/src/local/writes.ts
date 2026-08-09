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
import { newLocalId } from './outbox';
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
  void sync.enqueue('setShoppingItemChecked', { itemId, checked }, itemId);
}

/** "I've been shopping" — drops everything ticked, keeps the rest. */
export function writeClearCheckedShoppingItems(items: readonly ShoppingListItem[]): void {
  const removed = items.filter((item) => item.checked).map((item) => item.id);
  if (removed.length === 0) return;
  void getStore().removeShoppingItems(removed);
  // No `entityId`: this is about the list, not one item. Queueing it against a
  // single item's id would make it wait behind that item's own backoff.
  void sync.enqueue('clearCheckedShoppingItems', {}, null);
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
export function writeComment(saveId: string, body: string): void {
  void sync.enqueue('addComment', { saveId, body }, saveId);
}

export function writeDeleteComment(saveId: string, commentId: string): void {
  void sync.enqueue('deleteComment', { saveId, commentId }, commentId);
}

export function writeVote(saveId: string, value: 1 | -1 | 0): void {
  void sync.enqueue('setVote', { saveId, value }, saveId);
}
