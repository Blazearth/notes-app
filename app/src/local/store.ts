/**
 * The local store — the app's read path.
 *
 * **The rule this exists to keep:** never make the user wait for data they
 * already had five seconds ago. Screens read from here (through `useLive`),
 * never from the network; `@/local/sync` is the only consumer of `repo` and
 * writes what it fetches into here. See `docs/local-first.md`.
 *
 * **Why the seam is at the store and not at SQL.** `expo-sqlite`'s web support
 * is alpha and needs Metro WASM config plus COEP/COOP headers for
 * `SharedArrayBuffer` — and headless Chrome on `expo start --web` is this
 * project's entire visual and interaction test harness (`docs/testing.md`).
 * Putting the harness behind an alpha WASM path would trade it for a feature.
 * So there are two implementations of the ~20 typed methods below —
 * `sqliteStore` on native, `memoryStore` (AsyncStorage-backed snapshot) on web
 * — rather than one SQL dialect and a SQL emulator.
 *
 * **`itemStates` is normalised out of the save row, deliberately.** Only three
 * endpoints populate `SaveResponse.itemStates` (`GET /v1/saves`,
 * `GET /v1/saves/{id}`, `PATCH .../item-state` use the 2-arg
 * `SaveResponse.from`); `/search`, `/related`, `/spaces/{id}/saves` and
 * `/groups/{id}/saves` omit it entirely. Storing a save from one of those
 * wholesale would silently erase every ticked checkbox. {@link LocalStore.putSaves}
 * strips the field before writing and only ever *adds* to `item_states`; the
 * readers re-attach. That is strictly better than a "merge, don't replace" rule
 * which would have to be remembered at six call sites and would be silently
 * wrong the first time someone forgot.
 */

import type {
  SaveResponse,
  ShoppingListItem,
  ShoppingListResponse,
  Space,
  SpaceMember,
} from '@/api/types';
import type { StoreTable } from './schema';

export type { StoreTable } from './schema';
export { KV, STORE_TABLES } from './schema';

/** Narrowing applied inside the store rather than by every caller. */
export interface FeedQuery {
  /** Archived saves sit out of every ordinary view — see `LibraryScreen`. */
  includeArchived?: boolean;
  /** `null` means "the private feed" (no space); omit for "anywhere". */
  spaceId?: string | null;
  knowledgeType?: string;
  /** `createdAt` desc (the feed's order) or `updatedAt` desc (last touched). */
  orderBy?: 'created' | 'updated';
  limit?: number;
}

export interface LocalStore {
  readonly kind: 'sqlite' | 'memory';

  /** Idempotent. Creates the schema and loads any persisted snapshot. */
  open(): Promise<void>;
  /** Everything, including the kv watermarks. Used on a user change / sign-out. */
  wipe(): Promise<void>;

  // ---------------------------------------------------------------- saves
  /**
   * Upsert by id. `itemStates` is stripped from each save before it is
   * written — see the note at the top of this file. `replaceAll` deletes rows
   * this batch does not mention, which is what a full library sync wants and
   * what a partial page (a space's saves, a search result) must never do.
   */
  putSaves(saves: SaveResponse[], options?: { replaceAll?: boolean }): Promise<void>;
  /** Optimistic local edit. Merges, unlike `putSaves`, which replaces the row. */
  patchSave(id: string, changes: Partial<SaveResponse>): Promise<void>;
  removeSave(id: string): Promise<void>;
  readFeed(query?: FeedQuery): Promise<SaveResponse[]>;
  readSave(id: string): Promise<SaveResponse | null>;
  /** Preserves the order of `ids`; silently skips ones not held locally. */
  readSavesByIds(ids: string[]): Promise<SaveResponse[]>;
  /**
   * `count(*) group by knowledge_type` over ready, non-archived saves,
   * descending. No precomputed counts table: this is sub-millisecond over a
   * local library, and a stored total is a thing that goes stale.
   */
  countByType(): Promise<[string, number][]>;
  /** Full replace of one save's item states — the server's own contract. */
  putItemStates(saveId: string, states: Record<string, Record<string, unknown>>): Promise<void>;

  // -------------------------------------------------------- entity state (K2)
  putEntityStates(
    states: Record<string, Record<string, unknown>>,
    options?: { replaceAll?: boolean },
  ): Promise<void>;
  readEntityStates(): Promise<Record<string, Record<string, unknown>>>;

  // --------------------------------------------------------------- spaces
  putSpaces(spaces: Space[], options?: { replaceAll?: boolean }): Promise<void>;
  removeSpace(id: string): Promise<void>;
  readSpaces(): Promise<Space[]>;
  readSpace(id: string): Promise<Space | null>;
  putSpaceMembers(spaceId: string, members: SpaceMember[]): Promise<void>;
  readSpaceMembers(spaceId: string): Promise<SpaceMember[]>;
  /** Every space's members in one read — what the Spaces list needs, minus the N+1. */
  readAllSpaceMembers(): Promise<Record<string, SpaceMember[]>>;

  // -------------------------------------------------------- shopping list
  putShoppingList(list: ShoppingListResponse): Promise<void>;
  patchShoppingItem(id: string, changes: Partial<ShoppingListItem>): Promise<void>;
  removeShoppingItems(ids: string[]): Promise<void>;
  /** `items` in the server's aisle-then-name order, `categories` as sent. */
  readShoppingList(): Promise<ShoppingListResponse>;

  // ------------------------------------------------------------------- kv
  putKv(key: string, value: unknown): Promise<void>;
  readKv<T>(key: string): Promise<T | null>;

  /** Returns an unsubscribe. Listeners are called after the write has landed. */
  subscribe(listener: (tables: readonly StoreTable[]) => void): () => void;
}

// --------------------------------------------------------------- change bus

/**
 * The reactive half of the store, shared by both implementations.
 *
 * Table-scoped rather than one global counter: the Library re-runs its query
 * when a save changes, not when the shopping list does. Notification is
 * synchronous and after the write, so a `useLive` re-read always sees the new
 * value.
 */
export class ChangeBus {
  private listeners = new Set<(tables: readonly StoreTable[]) => void>();

  subscribe(listener: (tables: readonly StoreTable[]) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  emit(...tables: StoreTable[]): void {
    if (this.listeners.size === 0) return;
    // Copied: a listener may unsubscribe itself while being notified.
    for (const listener of [...this.listeners]) {
      try {
        listener(tables);
      } catch {
        // A broken screen must not stop the rest of the app from updating.
      }
    }
  }
}

// ------------------------------------------------------------ row mapping

/** The denormalised columns beside `json`, so the feed's order is an index read. */
export interface SaveRow {
  id: string;
  createdAt: string;
  updatedAt: string;
  status: string;
  knowledgeType: string | null;
  spaceId: string | null;
  lifecycleStatus: string | null;
  favorite: number;
  archived: number;
  title: string | null;
  /** `SaveResponse` minus `itemStates`, as JSON. */
  json: string;
}

function usable(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.toLowerCase() !== '[unclear]';
}

/**
 * `coalesce(title, name)` — the same trap that has now broken `saveTitle()`,
 * `search_tsv` (V3) and `SpaceService` in turn: `place` names its title field
 * `name`, not `title`.
 */
export function deriveTitle(save: SaveResponse): string | null {
  const data = save.structuredData;
  if (!data) return null;
  if (usable(data.title)) return data.title.trim();
  if (usable(data.name)) return data.name.trim();
  return null;
}

export function toRow(save: SaveResponse): SaveRow {
  // The one place `itemStates` leaves the payload. Everything downstream of
  // here — sqlite and memory alike — is storing a save that cannot carry a
  // stale (or absent) item-state map.
  const { itemStates: _dropped, ...rest } = save;
  return {
    id: save.id,
    createdAt: save.createdAt,
    updatedAt: save.updatedAt,
    status: save.status,
    knowledgeType: save.knowledgeType ?? null,
    spaceId: save.spaceId ?? null,
    lifecycleStatus: save.lifecycleStatus ?? null,
    favorite: save.favorite ? 1 : 0,
    archived: save.archived ? 1 : 0,
    title: deriveTitle(save),
    json: JSON.stringify(rest),
  };
}

/** Re-attaches item states, so screens keep seeing `SaveResponse` unchanged. */
export function fromRow(row: Pick<SaveRow, 'json'>, itemStates?: Record<string, Record<string, unknown>>): SaveResponse {
  const save = JSON.parse(row.json) as SaveResponse;
  if (itemStates && Object.keys(itemStates).length > 0) save.itemStates = itemStates;
  return save;
}

/** The comparator behind every `readFeed` — newest first, ties broken by id. */
export function compareSaves(a: SaveResponse, b: SaveResponse, orderBy: 'created' | 'updated'): number {
  const left = orderBy === 'updated' ? a.updatedAt : a.createdAt;
  const right = orderBy === 'updated' ? b.updatedAt : b.createdAt;
  if (left === right) return a.id < b.id ? 1 : -1;
  return left < right ? 1 : -1;
}

export function matchesQuery(save: SaveResponse, query: FeedQuery): boolean {
  if (!query.includeArchived && save.archived) return false;
  if (query.spaceId !== undefined && (save.spaceId ?? null) !== query.spaceId) return false;
  if (query.knowledgeType !== undefined && save.knowledgeType !== query.knowledgeType) return false;
  return true;
}
