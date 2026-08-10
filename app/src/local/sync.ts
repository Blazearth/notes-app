/**
 * The sync engine — the only consumer of `repo`, in both directions.
 *
 * `repo` stays exactly what it was (a plain module constant, `apiRepository` or
 * `mockRepository`, chosen by `USE_MOCK_DATA`) and is demoted to the network
 * layer: reads are fetched *into* the store here and screens read the store;
 * writes land in the store and in the outbox, and drain from here. Two
 * consequences worth stating:
 *
 * - **Mock mode stops being a bypass and becomes a fixture.** With the engine as
 *   `repo`'s only caller, `USE_MOCK_DATA` seeds the local store from
 *   `mockRepository` and every screen then exercises the real local-first path
 *   rather than a second one that only exists in development.
 * - **Reads never touch the network.** Nothing here is awaited by a render.
 *   Every task writes to the store and the store notifies; a screen that has
 *   cached data paints it immediately and updates in place when a task lands.
 *
 * **L4 changed what "sync" means here.** L1/L2 had no choice but fetch-and-
 * replace, because the server had no `since`, no cursor and no tombstones. It
 * has all three now (`V15__sync.sql`), so {@link SyncEngine.syncDelta} is the
 * primary read: one windowed request that replaces the paged walk of
 * `GET /v1/saves`, `GET /v1/spaces`, one member list per Space and three
 * collection calls whose only purpose was harvesting entity state. On a warm
 * client it returns empty lists.
 *
 * The full pulls did not go away, and that is deliberate — see
 * {@link SyncEngine.refreshAll}.
 */

import { AppState, type AppStateStatus } from 'react-native';
import * as Network from 'expo-network';

import { ApiError, type ApiErrorKind } from '@/api/client';
import type { DigestResponse, MeResponse, SaveResponse, SyncResponse } from '@/api/types';
import { repo } from '@/data';
import {
  dispositionFor,
  isLocalId,
  nextAttemptAt,
  nextWakeMs,
  randomId,
  selectNext,
  type OutboxEntry,
  type OutboxOp,
  type OutboxPayloads,
} from './outbox';
import { KV } from './schema';
import { splitTombstoneId } from './store';
import { getStore, openStore } from './index';

export type SyncTask =
  /** `GET /v1/sync` — the primary read. Everything that changed, in one call. */
  | 'delta'
  /** The reaping full pull. See {@link SyncEngine.refreshAll}. */
  | 'saves'
  | 'spaces'
  | 'spaceMembers'
  | 'entityStates'
  | 'shoppingList'
  | 'me'
  | 'digest'
  /** Draining queued local writes. */
  | 'outbox';

const ALL_TASKS: SyncTask[] = [
  'delta',
  'saves',
  'spaces',
  'spaceMembers',
  'entityStates',
  'shoppingList',
  'me',
  'digest',
  'outbox',
];

export interface SyncStatus {
  /** Tasks in flight right now. */
  running: readonly SyncTask[];
  /** Last failure per task, cleared on the next success. */
  errors: Readonly<Partial<Record<SyncTask, ApiError>>>;
  /**
   * Tasks that have completed at least once **in this install**, not this
   * session — hydrated from the store at bootstrap. This is what tells an
   * empty screen apart from a screen that has never been filled, which is the
   * difference between an honest empty state and a wrongly-hidden spinner.
   */
  completed: Readonly<Partial<Record<SyncTask, boolean>>>;
  /**
   * The queue is holding because the session expired. Nothing can succeed until
   * it refreshes, so spending each entry's retry budget discovering that would
   * empty the budget for reasons unrelated to the entries.
   */
  outboxPaused: boolean;
}

/** `GET /v1/saves` is paged; the reaping full pull needs the whole library. */
const PAGE_SIZE = 100;
/**
 * A hard stop on the walk. At the library sizes this app will see for a long
 * time this is never reached; if it ever is, the honest failure is "the newest
 * 5,000 saves are local" rather than an unbounded loop against a paging bug.
 */
const MAX_PAGES = 50;

/** Rows per entity type per `GET /v1/sync` page. The server clamps it. */
const SYNC_PAGE_SIZE = 200;
/** Same reasoning as `MAX_PAGES`, for the delta's `hasMore` loop. */
const MAX_SYNC_PAGES = 100;
/** A bound on one drain, so a queue that keeps refilling cannot spin forever. */
const MAX_DRAIN_STEPS = 100;

/**
 * The types whose collections are entity-bearing (K1's shape 1). Only the
 * reaping refresh needs this now: the delta carries entity state directly, so
 * the ordinary path no longer spends three requests harvesting it out of the
 * collection endpoint.
 */
const ENTITY_BEARING_TYPES = ['recommendation_list', 'itinerary', 'checklist'] as const;

class SyncEngine {
  private running = new Set<SyncTask>();
  private errors: Partial<Record<SyncTask, ApiError>> = {};
  private completed: Partial<Record<SyncTask, boolean>> = {};
  private listeners = new Set<() => void>();
  private snapshot: SyncStatus = { running: [], errors: {}, completed: {}, outboxPaused: false };
  /** Deduplicates concurrent identical tasks — two screens mounting at once. */
  private inFlight = new Map<SyncTask, Promise<void>>();

  private outboxPaused = false;
  private wakeTimer: ReturnType<typeof setTimeout> | null = null;
  private teardown: (() => void) | null = null;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** Stable identity between emissions, so `useSyncExternalStore` behaves. */
  getStatus = (): SyncStatus => this.snapshot;

  private emit() {
    this.snapshot = {
      running: [...this.running],
      errors: { ...this.errors },
      completed: { ...this.completed },
      outboxPaused: this.outboxPaused,
    };
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * Ties the store to a user. Signing in as somebody else must not show the
   * previous account's rows for even one frame, and the store is the one place
   * that survives a sign-out, so the check belongs here rather than in a
   * provider's effect.
   */
  async bootstrap(userId: string | null): Promise<void> {
    const store = await openStore();
    const owner = await store.readKv<string>(KV.ownerUserId);
    if (userId && owner && owner !== userId) {
      // Wipes the outbox too. It holds writes made as the previous user, and
      // replaying them under a new session would attribute one person's edits to
      // another — worse than losing them, which is what signing out already
      // means for anything unsent.
      await store.wipe();
      this.completed = {};
      this.errors = {};
      this.emit();
    }
    if (userId && owner !== userId) await store.putKv(KV.ownerUserId, userId);

    const marks = await Promise.all(
      ALL_TASKS.map(async (task) => [task, await store.readKv<number>(KV.syncedAt(task))] as const),
    );
    for (const [task, at] of marks) if (at) this.completed[task] = true;
    // A session that expired while the app was closed left the queue paused in
    // memory only, and this bootstrap follows a fresh sign-in — so anything
    // still queued is worth another attempt.
    this.outboxPaused = false;
    this.emit();
  }

  /** Sign-out. Everything local goes; a second account never sees the first's rows. */
  async reset(): Promise<void> {
    const store = await openStore();
    await store.wipe();
    this.completed = {};
    this.errors = {};
    this.outboxPaused = false;
    this.emit();
  }

  private run(task: SyncTask, work: () => Promise<void>): Promise<void> {
    const existing = this.inFlight.get(task);
    if (existing) return existing;

    this.running.add(task);
    this.emit();

    const promise = (async () => {
      try {
        await work();
        delete this.errors[task];
        this.completed[task] = true;
        await getStore().putKv(KV.syncedAt(task), Date.now());
      } catch (error) {
        // Failure is never fatal: the store keeps whatever it already had, so
        // the screen shows the last good data plus (if it chooses to) the
        // error. That is the entire point of reading locally.
        this.errors[task] =
          error instanceof ApiError ? error : new ApiError('server', 'Something went wrong', null);
      } finally {
        this.running.delete(task);
        this.inFlight.delete(task);
        this.emit();
      }
    })();

    this.inFlight.set(task, promise);
    return promise;
  }

  // ------------------------------------------------------------- the delta

  /**
   * `GET /v1/sync`, looped until the server says there is no more.
   *
   * Two properties do all the work here, and neither is obvious:
   *
   * - **The cursor comes from the server, never from `Date.now()`.** A phone
   *   running two seconds fast would otherwise step its watermark past rows it
   *   never received, and the loss would be silent and permanent.
   * - **The watermark advances only after a page has been applied.** If apply
   *   throws, the cursor stays where it was and the whole page arrives again —
   *   harmless, because every apply below is an upsert by id. That is also what
   *   makes the server's deliberate window overlap safe (`SyncWindow`).
   */
  syncDelta(): Promise<void> {
    return this.run('delta', async () => {
      const store = getStore();
      let cursor = await store.readKv<string>(KV.syncCursor);
      for (let page = 0; page < MAX_SYNC_PAGES; page += 1) {
        const response = await repo.pullSync(cursor, SYNC_PAGE_SIZE);
        await applyDelta(response);
        cursor = response.until;
        await store.putKv(KV.syncCursor, cursor);
        if (!response.hasMore) break;
      }
    });
  }

  // ------------------------------------------------------------- full pulls

  /**
   * The whole library, walked to exhaustion, replacing what is stored.
   *
   * Not the ordinary path any more — the delta is. This exists for
   * pull-to-refresh, where its one distinguishing property matters: `replaceAll`
   * reaps rows the server no longer returns, which is the only way to recover
   * from a *missed* tombstone. A delta cannot do that by construction, and
   * "swipe down and it fixes itself" is a much better answer to a lost deletion
   * than a cache that is quietly wrong forever.
   */
  syncSaves(): Promise<void> {
    return this.run('saves', async () => {
      const all: SaveResponse[] = [];
      for (let page = 0; page < MAX_PAGES; page += 1) {
        const batch = await repo.listSaves(page, PAGE_SIZE);
        all.push(...batch);
        if (batch.length < PAGE_SIZE) break;
      }
      // `replaceAll` is safe here and *only* here: this is the one task that
      // has seen the entire library, so a row it does not mention really is
      // gone. A partial page (a space's saves, a search result) must never
      // pass this flag. `local:` rows are exempt inside the store — the server
      // has never heard of them, so their absence proves nothing.
      await getStore().putSaves(all, { replaceAll: true });
    });
  }

  syncSpaces(): Promise<void> {
    return this.run('spaces', async () => {
      const spaces = await repo.listSpaces();
      await getStore().putSpaces(spaces, { replaceAll: true });
    });
  }

  /**
   * Members for every Space, in the background.
   *
   * The delta carries these now, so this is part of the reaping refresh rather
   * than the ordinary path. Failing one Space's members must not lose the
   * others', so each is written as it arrives.
   */
  syncSpaceMembers(): Promise<void> {
    return this.run('spaceMembers', async () => {
      const store = getStore();
      const spaces = await store.readSpaces();
      await Promise.all(
        spaces.map(async (space) => {
          try {
            await store.putSpaceMembers(space.id, await repo.listSpaceMembers(space.id));
          } catch {
            // Counts-only card, rather than no card.
          }
        }),
      );
    });
  }

  /**
   * K2 state for every merged entity, harvested from the collection payload.
   *
   * Also no longer on the ordinary path — the delta carries entity state
   * directly, which is three fewer requests per sync. It stays for the reaping
   * refresh and for the "discard a failed write" path, which needs to re-read
   * the server's truth for an entity and has no per-entity GET to do it with.
   */
  syncEntityStates(): Promise<void> {
    return this.run('entityStates', async () => {
      const store = getStore();
      const present = new Set((await store.countByType()).map(([type]) => type));
      const types = ENTITY_BEARING_TYPES.filter((type) => present.has(type));
      const states: Record<string, Record<string, unknown>> = {};
      for (const type of types) {
        for (const entity of await repo.listCollectionEntities(type)) {
          if (entity.state) states[entity.entityKey] = entity.state;
        }
      }
      // Not `replaceAll`: a type with no saves left locally is not evidence
      // that its entity state was deleted server-side, and dropping it would
      // silently un-watch things.
      await store.putEntityStates(states);
    });
  }

  /**
   * The shopping list, in full.
   *
   * Deliberately **not** in the delta, and the reason is ordering rather than
   * cost: the list is stored in the server's aisle-then-name order so a shopper
   * walks the shop once, and a stream of individual item rows carries no
   * position to rebuild that from. Its *deletions* do ride the tombstone list,
   * which is the half a full pull handles worst.
   */
  syncShoppingList(): Promise<void> {
    return this.run('shoppingList', async () => {
      await getStore().putShoppingList(await repo.getShoppingList());
    });
  }

  syncMe(): Promise<void> {
    return this.run('me', async () => {
      await getStore().putKv(KV.me, await repo.getMe());
    });
  }

  syncDigest(): Promise<void> {
    return this.run('digest', async () => {
      const digest = await repo.getWeeklyDigest();
      // `pending` means the server just enqueued the Gemini call this request
      // triggered — storing it would pin "nothing to show" into the cache, so
      // only a finished digest is kept and the next sync asks again.
      if (digest.status !== 'pending') await getStore().putKv(KV.digest, digest);
    });
  }

  // ---------------------------------------------------------- the outbox

  /**
   * Sends queued writes, one at a time, until nothing is due.
   *
   * Serial rather than parallel, on purpose: two writes about the same save have
   * to land in the order the user made them, and a queue that only orders
   * *within* an entity while running several at once is a much harder thing to
   * be sure of than a queue that sends one write at a time. The queue is short
   * by nature — it is what has not reached the server *yet*, not a work backlog.
   */
  drainOutbox(): Promise<void> {
    if (this.outboxPaused) return Promise.resolve();
    return this.run('outbox', async () => {
      const store = getStore();
      for (let step = 0; step < MAX_DRAIN_STEPS; step += 1) {
        const entries = await store.readOutbox();
        const entry = selectNext(entries, Date.now());
        if (!entry) break;

        try {
          await send(entry);
          await store.removeOutbox(entry.id);
        } catch (error) {
          const kind: ApiErrorKind = error instanceof ApiError ? error.kind : 'server';
          const message = error instanceof Error ? error.message : 'Something went wrong';
          const disposition = dispositionFor(kind);

          if (disposition === 'pause') {
            this.outboxPaused = true;
            this.emit();
            break;
          }

          const attempts = entry.attempts + 1;
          if (disposition === 'fail') {
            await store.updateOutbox(entry.id, { attempts, status: 'failed', lastError: message });
            // A save that will never exist server-side cannot be flagged,
            // filed or ticked there either. Failing the dependents now is what
            // stops them retrying a 404 until the user reinstalls.
            if (entry.op === 'createSave' && entry.entityId !== null) {
              for (const other of entries) {
                if (other.id !== entry.id && other.entityId === entry.entityId) {
                  await store.updateOutbox(other.id, {
                    status: 'failed',
                    lastError: 'The save this change belongs to could not be created.',
                  });
                }
              }
            }
          } else {
            await store.updateOutbox(entry.id, {
              attempts,
              nextAttemptAt: nextAttemptAt(attempts, Date.now()),
              lastError: message,
            });
          }
        }
      }
      await this.scheduleWake();
    });
  }

  /**
   * Queues a write and tries to send it immediately.
   *
   * The caller has already written the optimistic value to the store, so nothing
   * here is awaited by a render — the drain is fire-and-forget by design. Every
   * screen goes through `@/local/writes`, which calls this.
   */
  async enqueue<O extends OutboxOp>(
    op: O,
    payload: OutboxPayloads[O],
    entityId: string | null = null,
  ): Promise<void> {
    await getStore().enqueueOutbox({
      op,
      payload,
      idempotencyKey: randomId(),
      entityId,
      createdAt: new Date().toISOString(),
    });
    void this.drainOutbox();
  }

  /** The session refreshed, so the queue can move again. */
  resumeOutbox(): void {
    if (!this.outboxPaused) return;
    this.outboxPaused = false;
    this.emit();
    void this.drainOutbox();
  }

  /**
   * Retries one failed entry by hand — the "try again" behind a surfaced
   * failure. Clears the terminal status rather than re-queueing a copy, so the
   * entry keeps its position and its idempotency key.
   */
  async retryOutbox(id: number): Promise<void> {
    await getStore().updateOutbox(id, {
      status: 'pending',
      attempts: 0,
      nextAttemptAt: null,
      lastError: null,
    });
    void this.drainOutbox();
  }

  /**
   * Gives up on one failed entry.
   *
   * Dropping the entry is only half of it: the store still holds the optimistic
   * value nobody will ever agree with, so this re-reads the server's truth for
   * whatever the entry was about. Discarding without that leaves a save showing
   * a favorite the server does not have — a lie the user has no way to correct.
   */
  async discardOutbox(id: number): Promise<void> {
    const store = getStore();
    const entry = (await store.readOutbox()).find((e) => e.id === id);
    await store.removeOutbox(id);
    if (!entry) return;
    if (entry.entityId !== null && !isLocalId(entry.entityId)) {
      if (entry.op === 'setEntityState') void this.syncEntityStates();
      else void this.pullSave(entry.entityId);
    }
    if (entry.op === 'setShoppingItemChecked' || entry.op === 'clearCheckedShoppingItems') {
      void this.syncShoppingList();
    }
    // A failed `createSave` leaves a `local:` row that will never become real.
    // Removing it is the only honest outcome: the save was never accepted.
    if (entry.op === 'createSave') {
      await store.removeSave((entry.payload as OutboxPayloads['createSave']).localId);
    }
  }

  /** One timer for the earliest backoff, rather than one per entry. */
  private async scheduleWake(): Promise<void> {
    if (this.wakeTimer) {
      clearTimeout(this.wakeTimer);
      this.wakeTimer = null;
    }
    const wait = nextWakeMs(await getStore().readOutbox(), Date.now());
    if (wait === null) return;
    this.wakeTimer = setTimeout(() => {
      this.wakeTimer = null;
      void this.drainOutbox();
    }, wait);
  }

  // -------------------------------------------------------- compositions

  /**
   * App start and sign-in.
   *
   * The delta is awaited because everything after it is either cheap or
   * secondary; nothing else is, since the store notifies per task and no screen
   * is waiting on a promise. The drain goes first: a queued write the user made
   * before closing the app should reach the server before a pull tells the app
   * what the server currently thinks, or the pull overwrites the local value
   * with a stale one and the drain then puts it back — visible as a flicker.
   */
  async syncAll(): Promise<void> {
    await this.drainOutbox();
    await this.syncDelta();
    void this.syncShoppingList();
    void this.syncMe();
    void this.syncDigest();
  }

  /**
   * Pull-to-refresh: a genuine full pull, not a delta, and awaited so the
   * spinner is honest.
   *
   * This is the only path that can recover from a deletion whose tombstone never
   * arrived, because it is the only one that reaps. Worth the extra requests
   * precisely because the user asked for it — it is not on any automatic
   * trigger.
   */
  async refreshAll(): Promise<void> {
    await this.drainOutbox();
    await Promise.all([this.syncSaves(), this.syncSpaces()]);
    await Promise.all([
      this.syncDelta(),
      this.syncEntityStates(),
      this.syncSpaceMembers(),
      this.syncShoppingList(),
      this.syncDigest(),
    ]);
  }

  /**
   * The triggers, registered once per signed-in session.
   *
   * Four of them, and each covers a case the others do not: app start (in
   * `syncAll`), returning to the foreground, connectivity coming back, and the
   * user pulling down. Connectivity is `expo-network` rather than NetInfo
   * because the project already leans on `expo-*` and this needs one listener,
   * not a library.
   */
  start(): () => void {
    this.teardown?.();

    const onAppState = (state: AppStateStatus) => {
      // Only the transition into `active`. A background/inactive tick is not a
      // moment anything changed, and syncing on it would spend requests while
      // nobody is looking.
      if (state === 'active') void this.syncAll();
    };
    const appStateSub = AppState.addEventListener('change', onAppState);

    let wasConnected = true;
    const networkSub = Network.addNetworkStateListener(({ isConnected }) => {
      const connected = isConnected !== false;
      // The edge, not the level: a connected→connected event fires on any
      // network change (wifi to cellular) and is not news.
      if (connected && !wasConnected) {
        this.resumeOutbox();
        void this.syncAll();
      }
      wasConnected = connected;
    });

    this.teardown = () => {
      appStateSub.remove();
      networkSub.remove();
      if (this.wakeTimer) {
        clearTimeout(this.wakeTimer);
        this.wakeTimer = null;
      }
      this.teardown = null;
    };
    return this.teardown;
  }

  /**
   * One save, on demand — a detail screen opened from a search result or a
   * cold deep link, and the poll while a just-created save is `processing`.
   */
  async pullSave(id: string): Promise<SaveResponse | null> {
    // A `local:` save exists nowhere but here; asking the server for it is a
    // guaranteed 404 and would surface as a load error on a save the user can
    // see perfectly well.
    if (isLocalId(id)) return getStore().readSave(id);
    try {
      const save = await repo.getSave(id);
      await getStore().putSaves([save]);
      return save;
    } catch {
      return null;
    }
  }

  /** A Space's own tab, on demand: its saves and members, into the store. */
  async pullSpace(id: string): Promise<void> {
    const store = getStore();
    const results = await Promise.allSettled([
      repo.getSpace(id),
      repo.listSpaceSaves(id),
      repo.listSpaceMembers(id),
    ]);
    const [space, saves, members] = results;
    if (space.status === 'fulfilled') await store.putSpaces([space.value]);
    // No `replaceAll`: this is one Space's page, not the whole library.
    if (saves.status === 'fulfilled') await store.putSaves(saves.value);
    if (members.status === 'fulfilled') await store.putSpaceMembers(id, members.value);
    if (space.status === 'rejected') throw space.reason;
  }
}

// ------------------------------------------------------------- applying a page

/**
 * One `GET /v1/sync` page, into the store.
 *
 * Upserts throughout, never replaces-the-set: a delta says what changed, so a
 * `replaceAll` here would delete every row the window happened not to mention.
 * The one exception is `spaceMembers`, where the server deliberately sends a
 * Space's *whole* member list rather than the changed rows — which is why that
 * one can use the replace-per-space write the app already had.
 */
async function applyDelta(response: SyncResponse): Promise<void> {
  const store = getStore();

  if (response.saves.length > 0) await store.putSaves(response.saves);

  for (const row of response.itemStates) {
    await store.putItemState(row.saveId, row.itemPath, row.state);
  }

  if (response.entityStates.length > 0) {
    const states: Record<string, Record<string, unknown>> = {};
    for (const row of response.entityStates) states[row.entityKey] = row.state;
    await store.putEntityStates(states);
  }

  if (response.overrides.length > 0) await store.putOverrides(response.overrides);

  if (response.spaces.length > 0) await store.putSpaces(response.spaces);
  for (const row of response.spaceMembers) {
    await store.putSpaceMembers(row.spaceId, row.members);
  }

  for (const deletion of response.deleted) {
    await applyDeletion(deletion.type, deletion.id);
  }
}

/**
 * Deletions are applied by type, and unknown types are ignored on purpose.
 *
 * `comment` and `vote` are written server-side so the deletion record is whole
 * rather than whole-for-five-of-seven-paths, but nothing local caches either —
 * comments are a detail-screen read and a vote is only ever seen as part of a
 * score. Ignoring them here (and anything a future server starts tombstoning)
 * is what keeps an older client working against a newer server.
 */
async function applyDeletion(type: string, id: string): Promise<void> {
  const store = getStore();
  // Two types carry a composite id joined by `|` — see `TombstoneService` and
  // `splitTombstoneId` for why it splits on the first separator only.
  const [head, tail] = splitTombstoneId(id);

  switch (type) {
    case 'space':
      await store.removeSpace(id);
      break;
    case 'space_member':
      await store.removeSpaceMember(head, tail);
      break;
    case 'shopping_item':
      await store.removeShoppingItems([id]);
      break;
    case 'collection_override':
      await store.removeOverride(head, tail);
      break;
    default:
      break;
  }
}

// ------------------------------------------------------------ sending a write

/**
 * How each queued op reaches the server, and what its response does to the
 * store.
 *
 * Adopting the echoed response wholesale is safe for exactly one reason: every
 * one of these endpoints is a **full replace**, never a merge. That is stated in
 * `SaveItemStateService`, `EntityStateService` and the shopping-list fold, and it
 * is what makes a queued write an absolute set rather than a delta — which in
 * turn is what makes retrying one harmless and last-write-wins the real
 * semantics rather than a compromise.
 */
const SENDERS: { [O in OutboxOp]: (entry: OutboxEntry<O>) => Promise<void> } = {
  async createSave(entry) {
    const { body, localId } = entry.payload;
    const save = await repo.createSave(body, entry.idempotencyKey);
    // One write: the row moves onto the real id, its item states follow, the
    // `pending` flag clears, and every other queued entry pointing at the local
    // id is rewritten. Doing them separately leaves a window where a screen
    // reads a save whose ticks belong to an id that no longer exists.
    await getStore().reconcileSaveId(localId, save);
  },

  async updateNote(entry) {
    const updated = await repo.updateNote(entry.payload.id, {
      title: entry.payload.title,
      body: entry.payload.body,
    });
    await getStore().patchSave(entry.payload.id, updated);
  },

  async setSaveFlags(entry) {
    const updated = await repo.setSaveFlags(entry.payload.id, entry.payload.flags);
    await getStore().patchSave(entry.payload.id, updated);
  },

  async setSaveLifecycle(entry) {
    const updated = await repo.setSaveLifecycle(entry.payload.id, entry.payload.lifecycleStatus);
    await getStore().patchSave(entry.payload.id, updated);
  },

  async setSaveSpace(entry) {
    const updated = await repo.setSaveSpace(entry.payload.id, entry.payload.spaceId);
    await getStore().patchSave(entry.payload.id, updated);
  },

  async deleteSave(entry) {
    await repo.deleteSave(entry.payload.id);
    // Server confirmed deletion — remove from store in case it reappeared via sync
    await getStore().removeSave(entry.payload.id);
  },

  async setSaveItemState(entry) {
    const updated = await repo.setSaveItemState(
      entry.payload.id,
      entry.payload.itemPath,
      entry.payload.state,
    );
    await getStore().patchSave(entry.payload.id, updated);
  },

  async setEntityState(entry) {
    const echoed = await repo.setEntityState(entry.payload.entityKey, entry.payload.state);
    await getStore().putEntityStates({ [entry.payload.entityKey]: echoed });
  },

  async setShoppingItemChecked(entry) {
    await repo.setShoppingItemChecked(entry.payload.itemId, entry.payload.checked);
  },

  async clearCheckedShoppingItems(entry) {
    // Scoped, deliberately: one button that cleared the Space's list *and* the
    // caller's own would be unrecoverable, so the two are different requests
    // rather than one request with a wider reach.
    if (entry.payload.spaceId) {
      await repo.clearCheckedSpaceShoppingItems(entry.payload.spaceId);
    } else {
      await repo.clearCheckedShoppingItems();
    }
  },

  async convertToShoppingList(entry) {
    await repo.convertToShoppingList(entry.payload.saveId);
    // The conversion is a queued *server* job that spends a Gemini request, so
    // the list is not updated by the time this resolves. Asking now would show
    // the list without the recipe on it; the next ordinary sync picks it up.
  },

  async addComment(entry) {
    // The entry's own key, minted once at enqueue and carried through every
    // retry. This is the only queued op that *creates* a row — every other one
    // is an absolute set, so replaying it is a no-op by construction — which
    // makes it the only one where a lost response could post the same thing
    // twice. `V16__idempotency.sql` is what the key finally reaches.
    await repo.addComment(entry.payload.saveId, entry.payload.body, entry.idempotencyKey);
  },

  async deleteComment(entry) {
    await repo.deleteComment(entry.payload.saveId, entry.payload.commentId);
  },

  async setVote(entry) {
    await repo.setVote(entry.payload.saveId, entry.payload.value);
  },

  async addEntityComment(entry) {
    // The second queued op that *creates* a row, so it carries the entry's own
    // key for the same reason `addComment` does — a lost response followed by a
    // retry is otherwise the difference between one remark and two identical
    // ones.
    await repo.addEntityComment(
      entry.payload.spaceId,
      entry.payload.entityKey,
      entry.payload.body,
      entry.idempotencyKey,
    );
  },

  async deleteEntityComment(entry) {
    await repo.deleteEntityComment(entry.payload.spaceId, entry.payload.commentId);
  },

  async pinInSpace(entry) {
    await repo.pinInSpace(
      entry.payload.spaceId,
      entry.payload.kind,
      entry.payload.subject,
      entry.payload.payload,
    );
  },

  async unpinInSpace(entry) {
    await repo.unpinInSpace(entry.payload.spaceId, entry.payload.pinId);
  },
};

function send(entry: OutboxEntry): Promise<void> {
  const sender = SENDERS[entry.op] as (e: OutboxEntry) => Promise<void>;
  if (!sender) {
    // An op this build does not know how to send — only reachable by downgrading
    // over a queue a newer build wrote. Permanent by construction, so it is
    // reported as such rather than retried forever.
    return Promise.reject(new ApiError('validation', 'This change is no longer supported.', null));
  }
  return sender(entry);
}

export const sync = new SyncEngine();

/** Convenience readers for the two kv-backed singletons. */
export const readMe = (): Promise<MeResponse | null> => getStore().readKv<MeResponse>(KV.me);
export const readDigest = (): Promise<DigestResponse | null> => getStore().readKv<DigestResponse>(KV.digest);
