/**
 * The store, as Maps plus an AsyncStorage snapshot.
 *
 * Used on web — where `expo-sqlite` is alpha and would cost this project its
 * only visual test harness (see `store.ts`) — and as the fallback if opening
 * SQLite fails on a device. It mirrors `schema.ts` table for table, so the two
 * implementations are interchangeable from above.
 *
 * **It persists, and that matters for verification.** A purely in-memory shim
 * would make "Home paints from cache on a second load" untestable in the one
 * environment this project can actually drive (headless Chrome on expo-web), so
 * the cold-start-from-cache path would ship unexercised. The snapshot is
 * written debounced and best-effort: a full library is well inside what
 * localStorage holds at these sizes, and a failure to write degrades to
 * "fetches again next launch", never to a broken app.
 */

import AsyncStorage from '@react-native-async-storage/async-storage';

import type { SaveResponse, ShoppingListItem, Space, SpaceMember } from '@/api/types';
import { KV, SCHEMA_VERSION, type StoreTable } from './schema';
import {
  ChangeBus,
  compareSaves,
  matchesQuery,
  type FeedQuery,
  type LocalStore,
} from './store';

const SNAPSHOT_KEY = 'weavr.local.snapshot.v1';
/** Long enough to coalesce a whole sync's writes, short enough to survive a quick kill. */
const SNAPSHOT_DEBOUNCE_MS = 250;

interface Snapshot {
  schemaVersion: number;
  saves: SaveResponse[];
  itemStates: [string, Record<string, Record<string, unknown>>][];
  entityStates: [string, Record<string, unknown>][];
  spaces: Space[];
  spaceMembers: [string, SpaceMember[]][];
  shoppingItems: ShoppingListItem[];
  kv: [string, unknown][];
}

export function createMemoryStore(): LocalStore {
  const bus = new ChangeBus();

  // `saves` holds the payload with `itemStates` already stripped, exactly like
  // the sqlite row does — the normalisation is a property of the store, not of
  // one backend.
  let saves = new Map<string, SaveResponse>();
  let itemStates = new Map<string, Record<string, Record<string, unknown>>>();
  let entityStates = new Map<string, Record<string, unknown>>();
  let spaces = new Map<string, Space>();
  let spaceMembers = new Map<string, SpaceMember[]>();
  let shoppingItems: ShoppingListItem[] = [];
  let kv = new Map<string, unknown>();

  let snapshotTimer: ReturnType<typeof setTimeout> | null = null;

  function schedulePersist() {
    if (snapshotTimer) clearTimeout(snapshotTimer);
    snapshotTimer = setTimeout(() => {
      snapshotTimer = null;
      void persist();
    }, SNAPSHOT_DEBOUNCE_MS);
  }

  async function persist() {
    const snapshot: Snapshot = {
      schemaVersion: SCHEMA_VERSION,
      saves: [...saves.values()],
      itemStates: [...itemStates.entries()],
      entityStates: [...entityStates.entries()],
      spaces: [...spaces.values()],
      spaceMembers: [...spaceMembers.entries()],
      shoppingItems,
      kv: [...kv.entries()],
    };
    try {
      await AsyncStorage.setItem(SNAPSHOT_KEY, JSON.stringify(snapshot));
    } catch {
      // Quota, or no storage at all. Next launch fetches instead of reading
      // cache — slower, never wrong.
    }
  }

  function touched(...tables: StoreTable[]) {
    schedulePersist();
    bus.emit(...tables);
  }

  /** Strip on the way in — see the `itemStates` note in `store.ts`. */
  function strip(save: SaveResponse): SaveResponse {
    const { itemStates: _dropped, ...rest } = save;
    return rest as SaveResponse;
  }

  function hydrate(save: SaveResponse): SaveResponse {
    const states = itemStates.get(save.id);
    return states && Object.keys(states).length > 0 ? { ...save, itemStates: states } : { ...save };
  }

  return {
    kind: 'memory',

    async open() {
      try {
        const raw = await AsyncStorage.getItem(SNAPSHOT_KEY);
        if (!raw) return;
        const snapshot = JSON.parse(raw) as Snapshot;
        // A schema bump drops rather than migrates: everything in here is a
        // cache of something the server can re-send, so re-fetching is strictly
        // cheaper than a migration path that has to stay correct forever.
        if (snapshot.schemaVersion !== SCHEMA_VERSION) return;
        saves = new Map(snapshot.saves.map((s) => [s.id, s]));
        itemStates = new Map(snapshot.itemStates);
        entityStates = new Map(snapshot.entityStates);
        spaces = new Map(snapshot.spaces.map((s) => [s.id, s]));
        spaceMembers = new Map(snapshot.spaceMembers);
        shoppingItems = snapshot.shoppingItems ?? [];
        kv = new Map(snapshot.kv);
      } catch {
        // Corrupt snapshot. Start empty — the sync will refill it.
      }
    },

    async wipe() {
      saves = new Map();
      itemStates = new Map();
      entityStates = new Map();
      spaces = new Map();
      spaceMembers = new Map();
      shoppingItems = [];
      kv = new Map();
      try {
        await AsyncStorage.removeItem(SNAPSHOT_KEY);
      } catch {
        // Best effort; the in-memory wipe above is what the app reads from.
      }
      bus.emit('saves', 'item_states', 'entity_states', 'spaces', 'space_members', 'shopping_items', 'kv');
    },

    // ------------------------------------------------------------- saves

    async putSaves(incoming, options) {
      if (options?.replaceAll) {
        const keep = new Set(incoming.map((s) => s.id));
        for (const id of [...saves.keys()]) {
          if (!keep.has(id)) {
            saves.delete(id);
            itemStates.delete(id);
          }
        }
      }
      for (const save of incoming) {
        saves.set(save.id, strip(save));
        // Only ever *added*: an endpoint that omits the field leaves whatever
        // the user has ticked exactly where it was.
        if (save.itemStates) itemStates.set(save.id, save.itemStates);
      }
      touched('saves', 'item_states');
    },

    async patchSave(id, changes) {
      const current = saves.get(id);
      if (!current) return;
      const { itemStates: incomingStates, ...rest } = changes;
      saves.set(id, { ...current, ...rest });
      if (incomingStates) itemStates.set(id, incomingStates);
      touched('saves', 'item_states');
    },

    async removeSave(id) {
      saves.delete(id);
      itemStates.delete(id);
      touched('saves', 'item_states');
    },

    async readFeed(query: FeedQuery = {}) {
      const out: SaveResponse[] = [];
      for (const save of saves.values()) {
        if (matchesQuery(save, query)) out.push(hydrate(save));
      }
      out.sort((a, b) => compareSaves(a, b, query.orderBy ?? 'created'));
      return query.limit !== undefined ? out.slice(0, query.limit) : out;
    },

    async readSave(id) {
      const save = saves.get(id);
      return save ? hydrate(save) : null;
    },

    async readSavesByIds(ids) {
      const out: SaveResponse[] = [];
      for (const id of ids) {
        const save = saves.get(id);
        if (save) out.push(hydrate(save));
      }
      return out;
    },

    async countByType() {
      const tally = new Map<string, number>();
      for (const save of saves.values()) {
        if (save.archived || save.status !== 'ready' || !save.knowledgeType) continue;
        tally.set(save.knowledgeType, (tally.get(save.knowledgeType) ?? 0) + 1);
      }
      return [...tally.entries()].sort((a, b) => b[1] - a[1]);
    },

    async putItemStates(saveId, states) {
      itemStates.set(saveId, states);
      touched('item_states');
    },

    // ------------------------------------------------------ entity state

    async putEntityStates(states, options) {
      if (options?.replaceAll) entityStates = new Map();
      for (const [key, value] of Object.entries(states)) entityStates.set(key, value);
      touched('entity_states');
    },

    async readEntityStates() {
      return Object.fromEntries(entityStates);
    },

    // ------------------------------------------------------------ spaces

    async putSpaces(incoming, options) {
      if (options?.replaceAll) {
        const keep = new Set(incoming.map((s) => s.id));
        for (const id of [...spaces.keys()]) {
          if (!keep.has(id)) {
            spaces.delete(id);
            spaceMembers.delete(id);
          }
        }
      }
      for (const space of incoming) spaces.set(space.id, space);
      touched('spaces');
    },

    async removeSpace(id) {
      spaces.delete(id);
      spaceMembers.delete(id);
      touched('spaces', 'space_members');
    },

    async readSpaces() {
      return [...spaces.values()];
    },

    async readSpace(id) {
      return spaces.get(id) ?? null;
    },

    async putSpaceMembers(spaceId, members) {
      spaceMembers.set(spaceId, members);
      touched('space_members');
    },

    async readSpaceMembers(spaceId) {
      return spaceMembers.get(spaceId) ?? [];
    },

    async readAllSpaceMembers() {
      return Object.fromEntries(spaceMembers);
    },

    // ---------------------------------------------------- shopping list

    async putShoppingList(list) {
      shoppingItems = list.items;
      kv.set(KV.shoppingCategories, list.categories);
      touched('shopping_items', 'kv');
    },

    async patchShoppingItem(id, changes) {
      shoppingItems = shoppingItems.map((item) => (item.id === id ? { ...item, ...changes } : item));
      touched('shopping_items');
    },

    async removeShoppingItems(ids) {
      const drop = new Set(ids);
      shoppingItems = shoppingItems.filter((item) => !drop.has(item.id));
      touched('shopping_items');
    },

    async readShoppingList() {
      return {
        items: shoppingItems.map((item) => ({ ...item })),
        categories: (kv.get(KV.shoppingCategories) as string[] | undefined) ?? [],
      };
    },

    // ---------------------------------------------------------------- kv

    async putKv(key, value) {
      kv.set(key, value);
      touched('kv');
    },

    async readKv<T>(key: string) {
      const value = kv.get(key);
      return (value === undefined ? null : (value as T));
    },

    subscribe: (listener) => bus.subscribe(listener),
  };
}
