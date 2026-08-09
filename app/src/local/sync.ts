/**
 * The sync engine — the only consumer of `repo`.
 *
 * `repo` stays exactly what it was (a plain module constant, `apiRepository` or
 * `mockRepository`, chosen by `USE_MOCK_DATA`) and is demoted to the network
 * layer: it is fetched *into* the store here, and screens read the store. Two
 * consequences worth stating:
 *
 * - **Mock mode stops being a bypass and becomes a fixture.** With the engine
 *   as `repo`'s only caller, `USE_MOCK_DATA` seeds the local store from
 *   `mockRepository` and every screen then exercises the real local-first path
 *   rather than a second one that only exists in development.
 * - **Reads never touch the network.** Nothing here is awaited by a render.
 *   Every task writes to the store and the store notifies; a screen that has
 *   cached data paints it immediately and updates in place when a task lands.
 *
 * This is L1/L2 of `docs/local-first.md`: full pulls, not deltas. The server
 * has no sync primitives yet (no `since`, no cursor, no tombstones — that is
 * L4's `V15__sync.sql`), so "sync" here means fetch-and-replace. The *shape* is
 * already the delta shape: tasks write through the store, and swapping a full
 * pull for a windowed one later changes this file and nothing above it.
 */

import { ApiError } from '@/api/client';
import type { DigestResponse, MeResponse, SaveResponse } from '@/api/types';
import { repo } from '@/data';
import { KV } from './schema';
import { getStore, openStore } from './index';

export type SyncTask =
  | 'saves'
  | 'spaces'
  | 'spaceMembers'
  | 'entityStates'
  | 'shoppingList'
  | 'me'
  | 'digest';

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
}

/** `GET /v1/saves` is paged; the derived views need the whole library. */
const PAGE_SIZE = 100;
/**
 * A hard stop on the walk. At the library sizes this app will see for a long
 * time this is never reached; if it ever is, the honest failure is "the newest
 * 5,000 saves are local" rather than an unbounded loop against a paging bug.
 */
const MAX_PAGES = 50;

/**
 * The types whose collections are entity-bearing (K1's shape 1). Entity state
 * is per (user, entityKey) and the server exposes it only joined into the
 * collection payload — there is no `GET /v1/entity-states` — so harvesting it
 * from there is the read path, not a workaround.
 */
const ENTITY_BEARING_TYPES = ['recommendation_list', 'itinerary', 'checklist'] as const;

class SyncEngine {
  private running = new Set<SyncTask>();
  private errors: Partial<Record<SyncTask, ApiError>> = {};
  private completed: Partial<Record<SyncTask, boolean>> = {};
  private listeners = new Set<() => void>();
  private snapshot: SyncStatus = { running: [], errors: {}, completed: {} };
  /** Deduplicates concurrent identical tasks — two screens mounting at once. */
  private inFlight = new Map<SyncTask, Promise<void>>();

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
      await store.wipe();
      this.completed = {};
      this.errors = {};
      this.emit();
    }
    if (userId && owner !== userId) await store.putKv(KV.ownerUserId, userId);

    const marks = await Promise.all(
      (['saves', 'spaces', 'spaceMembers', 'entityStates', 'shoppingList', 'me', 'digest'] as SyncTask[]).map(
        async (task) => [task, await store.readKv<number>(KV.syncedAt(task))] as const,
      ),
    );
    for (const [task, at] of marks) if (at) this.completed[task] = true;
    this.emit();
  }

  /** Sign-out. Everything local goes; a second account never sees the first's rows. */
  async reset(): Promise<void> {
    const store = await openStore();
    await store.wipe();
    this.completed = {};
    this.errors = {};
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

  // ------------------------------------------------------------- tasks

  /**
   * The whole library, walked to exhaustion.
   *
   * Deriving groups and collections locally requires holding *all* the saves,
   * not page 0 — and the point at which that stops being fine is the same point
   * at which the server's own full-library scan in `GroupService` stops being
   * fine, so neither side is newly constrained by it.
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
      // pass this flag.
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
   * This is the N+1 the Spaces list used to do on every focus, moved off the
   * render path: the list now paints from the store immediately and the avatar
   * stacks fill in. Failing one Space's members must not lose the others', so
   * each is written as it arrives.
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

  /** K2 state for every merged entity, harvested from the collection payload. */
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

  // -------------------------------------------------------- compositions

  /**
   * App start and sign-in. Ordered by what the user sees first — saves feed
   * Home, the Library, groups and collections all at once — but not awaited in
   * sequence beyond that, since the store notifies per task.
   */
  async syncAll(): Promise<void> {
    await this.syncSaves();
    // Entity state is meaningless before the saves it keys off are local, and
    // it reads `countByType()` to decide which types to ask for at all.
    void this.syncEntityStates();
    await this.syncSpaces();
    void this.syncSpaceMembers();
    void this.syncShoppingList();
    void this.syncMe();
    void this.syncDigest();
  }

  /** Pull-to-refresh: the same full pull, awaited so the spinner is honest. */
  async refreshAll(): Promise<void> {
    await Promise.all([this.syncSaves(), this.syncSpaces()]);
    await Promise.all([
      this.syncEntityStates(),
      this.syncSpaceMembers(),
      this.syncShoppingList(),
      this.syncDigest(),
    ]);
  }

  /**
   * One save, on demand — a detail screen opened from a search result or a
   * cold deep link, and the poll while a just-created save is `processing`.
   */
  async pullSave(id: string): Promise<SaveResponse | null> {
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

export const sync = new SyncEngine();

/** Convenience readers for the two kv-backed singletons. */
export const readMe = (): Promise<MeResponse | null> => getStore().readKv<MeResponse>(KV.me);
export const readDigest = (): Promise<DigestResponse | null> => getStore().readKv<DigestResponse>(KV.digest);
