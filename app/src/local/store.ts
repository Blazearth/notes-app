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
import type { OutboxDraft, OutboxEntry } from './outbox';
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
  /**
   * When set, only saves whose `userId` matches are returned.
   * Used by Library and Home to exclude space-mates' saves from the
   * personal feed while keeping all of the caller's own saves (whether
   * or not they are in a shared space).
   */
  ownedByUserId?: string;
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
   *
   * **`replaceAll` never reaps a `local:` row.** A save created offline exists
   * only here until its POST lands, so a full pull cannot possibly mention it —
   * and reaping it would delete the user's save and leave the outbox entry that
   * creates it pointing at nothing.
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
  /**
   * One item's state, upserted. What a delta row carries — unlike
   * {@link putItemStates}, this leaves every other path on the save alone,
   * because the delta says what changed rather than what the set now is.
   */
  putItemState(saveId: string, itemPath: string, state: Record<string, unknown>): Promise<void>;
  removeItemState(saveId: string, itemPath: string): Promise<void>;

  /**
   * Moves an offline-created save onto its real server id, in one write.
   *
   * Four things have to move together or the save is visibly broken: the row
   * itself, the item states keyed on the old id, the outbox entries that still
   * reference it, and the `pending` flag. Doing them separately leaves a window
   * where a screen reads a save whose ticks belong to an id that no longer
   * exists.
   */
  reconcileSaveId(localId: string, real: SaveResponse): Promise<void>;

  // -------------------------------------------------------- entity state (K2)
  putEntityStates(
    states: Record<string, Record<string, unknown>>,
    options?: { replaceAll?: boolean },
  ): Promise<void>;
  readEntityStates(): Promise<Record<string, Record<string, unknown>>>;
  removeEntityState(entityKey: string): Promise<void>;

  // ------------------------------------------------- collection overrides (K4)
  putOverrides(
    rows: { overrideType: string; subjectKey: string; payload: Record<string, unknown> }[],
    options?: { replaceAll?: boolean },
  ): Promise<void>;
  readOverrides(): Promise<
    { overrideType: string; subjectKey: string; payload: Record<string, unknown> }[]
  >;
  removeOverride(overrideType: string, subjectKey: string): Promise<void>;

  // --------------------------------------------------------------- spaces
  putSpaces(spaces: Space[], options?: { replaceAll?: boolean }): Promise<void>;
  removeSpace(id: string): Promise<void>;
  readSpaces(): Promise<Space[]>;
  readSpace(id: string): Promise<Space | null>;
  putSpaceMembers(spaceId: string, members: SpaceMember[]): Promise<void>;
  removeSpaceMember(spaceId: string, userId: string): Promise<void>;
  readSpaceMembers(spaceId: string): Promise<SpaceMember[]>;
  /** Every space's members in one read — what the Spaces list needs, minus the N+1. */
  readAllSpaceMembers(): Promise<Record<string, SpaceMember[]>>;

  // -------------------------------------------------------- shopping list
  putShoppingList(list: ShoppingListResponse): Promise<void>;
  patchShoppingItem(id: string, changes: Partial<ShoppingListItem>): Promise<void>;
  removeShoppingItems(ids: string[]): Promise<void>;
  /** `items` in the server's aisle-then-name order, `categories` as sent. */
  readShoppingList(): Promise<ShoppingListResponse>;

  // --------------------------------------------------------------- outbox
  /** Assigns the id, which is also the queue's order. */
  enqueueOutbox(draft: OutboxDraft): Promise<OutboxEntry>;
  /** Every entry, `failed` ones included, in id order. */
  readOutbox(): Promise<OutboxEntry[]>;
  updateOutbox(id: number, changes: Partial<OutboxEntry>): Promise<void>;
  removeOutbox(id: number): Promise<void>;

  // --------------------------------------------------------------- search
  /**
   * Full-text search over the local library, ranked, best first.
   *
   * FTS5 `MATCH` on native and a linear scan on web — the same signature and
   * the same *match set* either way, because both are built from the pure
   * helpers below. Only the ranking differs (bm25 against a hand-rolled score),
   * which is a difference in ordering rather than in what is findable.
   *
   * Mirrors the server's corpus deliberately: `ready` saves only, archived
   * included. `GET /v1/saves/search` filters on `status = 'ready'` and says
   * nothing about `archived`, and the two halves are merged into one list — a
   * local half that answered a different question would show results appearing
   * and disappearing as the server's reply landed.
   */
  searchLocal(query: string, limit?: number): Promise<SaveResponse[]>;

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

/**
 * Splits a tombstone's `entity_id` into the composite key the store is keyed on.
 *
 * Two of the server's six tombstone types carry two values joined by `|` (see
 * `TombstoneService`): `space_member` is `"<spaceId>|<userId>"` and
 * `collection_override` is `"<overrideType>|<subjectKey>"`. **On the first
 * separator only** — a collection override's subject key is an entity key, which
 * is arbitrary user-derived text (`"screen:blue box"`), so splitting on every
 * separator would truncate it. The scalar types come back as `[id, '']`.
 */
export function splitTombstoneId(id: string): [string, string] {
  const at = id.indexOf('|');
  return at < 0 ? [id, ''] : [id.slice(0, at), id.slice(at + 1)];
}

// ------------------------------------------------------------ local search
//
// The indexed text, and the query that runs against it. Pure, and shared by
// both store implementations so "what is findable" has exactly one definition
// — the FTS5 half and the linear-scan half differ only in how they rank.

/**
 * The text of a save, as two fields, following V11's rule exactly.
 *
 * **Scalar leaves only, keys never.** V4 indexed `jsonb_path_query_array(data,
 * '$.*')::text`, which serialises an object array *with its keys* — so "name",
 * "ingredients", "sets" and "rest" became terms every save of that type shared,
 * and "rest" is a plausible real query. V11 replaced it with a walk that keeps
 * scalars at any depth; this is that walk, in TypeScript.
 *
 * **`[unclear]` is stripped** (V4's own reason: the sentinel appears in most
 * saves, so indexing it makes a term nearly all of them match), and the title
 * is `coalesce(title, name)` — the `place` trap that has now broken
 * `saveTitle()`, `search_tsv` and `SpaceService` in turn.
 *
 * One honest gap against the server: weight B includes `raw_caption`, which
 * `SaveResponse` does not carry, so a phrase that appears only in the original
 * caption is findable on the server and not locally. The merge in
 * `SearchScreen` is what covers it.
 */
export function searchText(save: SaveResponse): { title: string; body: string } {
  const values: string[] = [];
  collectScalars(save.structuredData, values, 0);
  return { title: deriveTitle(save) ?? '', body: values.join(' ') };
}

/** Guards against a pathological structure, not against real data. */
const MAX_SEARCH_DEPTH = 12;

function collectScalars(value: unknown, out: string[], depth: number): void {
  if (value === null || value === undefined || depth > MAX_SEARCH_DEPTH) return;
  if (Array.isArray(value)) {
    for (const item of value) collectScalars(item, out, depth + 1);
    return;
  }
  if (typeof value === 'object') {
    // Values only — the keys are the schema's vocabulary, not the save's.
    for (const item of Object.values(value as Record<string, unknown>)) {
      collectScalars(item, out, depth + 1);
    }
    return;
  }
  if (typeof value === 'string') {
    if (usable(value)) out.push(value.trim());
    return;
  }
  if (typeof value === 'number' || typeof value === 'boolean') out.push(String(value));
}

/**
 * A query into comparable terms: lower-cased, accent-preserving, split on
 * anything that is not a letter, digit or hyphen.
 *
 * The same sanitising the server applies to a `to_tsquery` fragment, for the
 * same reason — a user typing `"` or `*` must not be able to produce a syntax
 * error out of an FTS5 `MATCH` expression.
 */
export function searchTerms(query: string): string[] {
  return query
    .toLowerCase()
    .split(/[^\p{L}\p{N}-]+/u)
    .map((term) => term.replace(/^-+|-+$/g, ''))
    .filter((term) => term.length > 0);
}

/**
 * The FTS5 `MATCH` expression: every term, each as a prefix.
 *
 * Quoted because a bare term can be an FTS5 keyword (`OR`, `NEAR`) or contain a
 * hyphen, which the parser reads as an operator. Terms are already stripped to
 * letters, digits and hyphens by {@link searchTerms}, so no quote can appear
 * inside one and no escaping is needed.
 */
export function ftsMatchQuery(terms: readonly string[]): string {
  return terms.map((term) => `"${term}"*`).join(' ');
}

/**
 * Whether one save matches, and how well — the linear-scan half.
 *
 * **Every term is a prefix, including the ones the server matches whole.**
 * `fullTextCandidates` prefixes only the last word, because extending every
 * term in a `tsquery` would widen a server-side scan; locally the cost is a
 * string comparison, and matching `chick past` against "chicken pasta" while
 * the user is still typing is the entire reason this half exists. So the local
 * result set is a superset of the server's, never a different one.
 *
 * @returns 0 when any term is unmatched — the terms are ANDed, as on the server
 */
export function scoreSearch(title: string, body: string, terms: readonly string[]): number {
  if (terms.length === 0) return 0;
  const titleWords = tokenize(title);
  const bodyWords = tokenize(body);
  let score = 0;
  for (const term of terms) {
    // Title matches weigh more, the same intent as `search_tsv`'s A/C weights.
    const inTitle = titleWords.some((word) => word.startsWith(term));
    const inBody = bodyWords.some((word) => word.startsWith(term));
    if (!inTitle && !inBody) return 0;
    if (inTitle) score += titleWords.includes(term) ? 5 : 4;
    if (inBody) score += bodyWords.includes(term) ? 2 : 1;
  }
  return score;
}

function tokenize(text: string): string[] {
  return text.toLowerCase().split(/[^\p{L}\p{N}-]+/u).filter((word) => word.length > 0);
}

/** The server's corpus, mirrored — see {@link LocalStore.searchLocal}. */
export function isSearchable(save: SaveResponse): boolean {
  return save.status === 'ready';
}

export function matchesQuery(save: SaveResponse, query: FeedQuery): boolean {
  if (!query.includeArchived && save.archived) return false;
  if (query.spaceId !== undefined && (save.spaceId ?? null) !== query.spaceId) return false;
  if (query.knowledgeType !== undefined && save.knowledgeType !== query.knowledgeType) return false;
  if (query.ownedByUserId !== undefined && save.userId !== query.ownedByUserId) return false;
  return true;
}
