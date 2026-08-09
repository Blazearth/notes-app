/**
 * The local layer's entry point.
 *
 * ```
 * Screens ── useLive(tables, read) ──► LocalStore ◄── sync ── repo (the ONLY consumer)
 * ```
 *
 * Everything above the store reads local data and re-renders on change;
 * everything below it is the network. A screen that still calls `repo`
 * directly keeps working exactly as before, which is what makes the migration
 * per-screen and reversible.
 */

import { createMemoryStore } from './memoryStore';
import { createSqliteStore } from './sqliteStore';
import type { LocalStore } from './store';

export type { FeedQuery, LocalStore, StoreTable } from './store';
export { KV } from './schema';
export { useLive, useLiveValue } from './useLive';

let store: LocalStore | null = null;
let opening: Promise<LocalStore> | null = null;

/**
 * Opens the store, once. Deliberately never rejects — this sits in
 * `SplashGate`'s blocking set, and a storage failure must degrade to "the app
 * fetches like it used to", not to a splash screen that never lifts.
 */
export function openStore(): Promise<LocalStore> {
  if (store) return Promise.resolve(store);
  opening ??= (async () => {
    // `createSqliteStore` returns null on web (Metro resolves
    // `sqliteStore.web.ts` there) — see that file for why.
    const native = createSqliteStore() as LocalStore | null;
    if (native) {
      try {
        await native.open();
        store = native;
        return native;
      } catch (error) {
        console.warn('[local] SQLite unavailable, falling back to the memory store', error);
      }
    }
    const memory = createMemoryStore();
    try {
      await memory.open();
    } catch {
      // An unreadable snapshot is a cold start, not a failure.
    }
    store = memory;
    return memory;
  })();
  return opening;
}

/**
 * The opened store. Safe to call from render: `SplashGate` blocks the whole
 * tree on {@link openStore} before any screen mounts, so by the time a
 * component exists this cannot be null.
 */
export function getStore(): LocalStore {
  if (!store) throw new Error('Local store read before openStore() resolved');
  return store;
}

/** True once {@link openStore} has resolved — for code paths outside the tree. */
export function storeIsOpen(): boolean {
  return store !== null;
}
