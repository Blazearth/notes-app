# Local-first: the phone becomes the database, the server becomes sync

This is the implementation plan for turning Weavr from a network-first REST
client into a local-first app. The problem it solves, stated once: **every
screen in the app today reads directly from the server, so every screen starts
empty.** A cold start renders a splash, then a spinner, then content. Tapping
Library refetches a list the app already had. Tapping into a group refetches
saves already in memory. On a bad connection the app is unusable; on no
connection it is a stack of error cards.

The target, stated as a rule:

> **Never make the user wait for data they already had five seconds ago.**

The mental model that follows from it — the server is a *sync* peer, not a
*storage* backend. The phone is the source of truth for interaction; the server
is the source of truth for reconciliation. Reads never touch the network.
Writes land locally first and drain from a queue.

```
today:    UI ──► Server
target:   Server ──► Sync engine ──► Local DB ──► UI
```

> **Status: L1 and L2 landed 2026-08-09.** Every read screen now reads the local
> store through `useLive`, groups and collections are derived on-device, and
> `@/local/sync` is the only consumer of `repo`. L3 (the outbox), L4 (delta
> sync + the server's `V15__sync.sql`) and L5 remain as written below.

---

## What the vision cannot mean here (read this before the phases)

Constraints earned from this codebase and this environment, not guessed.

1. **`Repository` cannot deliver stale-while-revalidate.** All 48 methods
   return `Promise<T>`, which resolves exactly once. Cached-then-fresh needs two
   emissions. The interface is not the seam; a reactive read layer beside it is.

2. **`repo` is a plain module constant, imported by 14 files**
   (`app/src/data/index.ts`). There is no provider and no injection point, which
   is *good news*: the sync engine can become the single consumer of `repo`
   without touching how it is constructed.

3. **`USE_MOCK_DATA` must keep working, and should get stronger.** If the sync
   engine is the only thing that calls `repo`, then mock mode seeds the local
   database from `mockRepository` and every screen exercises the real
   local-first path. Mock mode stops being a bypass and becomes a fixture.

4. **expo-sqlite's web support is alpha and would break the test harness.**
   Measured against the Expo docs, not assumed: web requires Metro WASM config
   *and* `Cross-Origin-Embedder-Policy` / `Cross-Origin-Opener-Policy` headers
   for `SharedArrayBuffer`, and is labelled alpha. Headless Chrome on
   `expo start --web` is this project's *entire* visual and interaction test
   harness (see [testing.md](testing.md)). Putting it behind an alpha WASM path
   with custom server headers trades the harness for a feature. **The storage
   seam is therefore at the store level, not the SQL level** — see below.

5. **The server has zero sync primitives.** A grep across `api/src` for
   `since|updatedAfter|ETag|If-None-Match|Last-Modified|cursor|deleted_at|tombstone|@Version`
   returns no production hits. No `saves (user_id, updated_at)` index either —
   a `where updated_at > ?` scan is unindexed today. Delta sync is a migration,
   not a query-param.

6. **There are no tombstones, and seven hard-delete paths.** A deleted Space
   simply vanishes from the server with no record, so a client that has it
   cached would keep it forever. Deletes are the part of sync that cannot be
   inferred.

7. **`SaveResponse.itemStates` is populated by only three endpoints.**
   `GET /v1/saves`, `GET /v1/saves/{id}` and `PATCH .../item-state` use the
   2-arg `SaveResponse.from`; `/search`, `/related`, `/spaces/{id}/saves` and
   `/groups/{id}/saves` use the 1-arg overload and omit the field entirely.
   Caching a save from the wrong endpoint would silently erase every checkbox
   the user has ticked.

8. **No device, no emulator, no local Postgres.** Verification is
   `npm run typecheck` → `expo export --platform web` → headless Chrome CDP →
   standalone `node --experimental-strip-types` on pure modules, plus
   `./mvnw test` and read-only JDBC probes against live Supabase. Anything
   claimed beyond that ladder is unverified and must say so.

---

## The model

### Where the seam goes

Three layers, and the existing `Repository` becomes the bottom one.

```
Screens
   │  useLive(query)            ← reactive reads, re-render on change
   ▼
Store  (app/src/local/store.ts) ← typed reads/writes + change bus
   │
   ├── sqliteStore.ts   (native)
   └── memoryStore.ts   (web / tests — AsyncStorage-backed snapshot)
   ▲
Sync engine (app/src/local/sync.ts) ── the ONLY consumer of `repo`
   │
   ▼
repo: Repository  ← unchanged; apiRepository | mockRepository
```

**Why the seam is at the store and not at SQL.** Writing a SQL emulator for
the web shim is absurd; writing a second implementation of ~15 typed methods
(`putSaves`, `readFeed`, `readSave`, `searchLocal`, …) is a morning's work and
keeps both platforms honest. FTS becomes `searchLocal(q)` — an FTS5 `MATCH` on
native, a linear scan on web, same signature.

**Screens migrate one at a time.** A screen that still does
`useEffect(() => repo.listSpaces())` keeps working exactly as today. Migration
is per-screen and reversible, which is what makes every phase shippable.

### Local schema (SQLite; the memory shim mirrors it as Maps)

```sql
create table saves (
  id            text primary key,
  created_at    text not null,
  updated_at    text not null,
  status        text,
  knowledge_type text,
  space_id      text,
  favorite      integer not null default 0,
  archived      integer not null default 0,
  lifecycle_status text,
  title         text,          -- denormalised: coalesce(title, name)
  json          text not null, -- the full SaveResponse, MINUS itemStates
  pending       integer not null default 0  -- has unsent local writes
);
create index saves_created_idx on saves (created_at desc);
create index saves_space_idx   on saves (space_id);
create index saves_type_idx    on saves (knowledge_type);

create virtual table saves_fts using fts5(
  save_id unindexed, title, body, tokenize='unicode61'
);

create table item_states  (save_id text, item_path text, json text, primary key (save_id, item_path));
create table entity_states(entity_key text primary key, json text);
create table overrides    (override_type text, subject_key text, value text, primary key (override_type, subject_key));
create table spaces       (id text primary key, json text not null);
create table space_members(space_id text, user_id text, json text, primary key (space_id, user_id));
create table shopping_items(id text primary key, json text);
create table kv           (key text primary key, value text);  -- me, digest, watermarks

create table outbox (
  id              integer primary key autoincrement,
  op              text not null,        -- a Repository method name
  payload         text not null,
  idempotency_key text not null,        -- generated ONCE at enqueue
  entity_id       text,
  created_at      text not null,
  attempts        integer not null default 0,
  next_attempt_at text,
  last_error      text,
  status          text not null default 'pending'  -- pending|failed
);
```

### The `itemStates` hazard, solved by normalisation rather than a merge rule

**`putSave()` strips `itemStates` before storing; only `putItemStates()` writes
the `item_states` table; `readSave()` re-attaches them.** A `SaveResponse` that
arrived from `/search` or `/related` can then be written wholesale with no
special case, because the field it omits does not live in the row it writes.
Screens keep seeing `SaveResponse.itemStates` exactly as they do today — the
normalisation is invisible above the store.

This is strictly better than a "merge, don't replace" rule, which would have to
be remembered at every one of the six write sites and would be silently wrong
the first time someone forgot.

### Derived data is derived locally, not fetched

`GET /v1/groups` and `GET /v1/collections` each load the user's **entire ready
library** as JPA entities and rebuild the whole tree per request. Both become
pure functions over the local store, and the endpoints stop being called.

- **Collections:** `app/src/collections/merge.ts` (536 lines) is already a
  working TS port of `CollectionService`'s merge core — `mergeType`,
  `buildTree`, `withDoneCount` — proven by `mockRepository`. It only needs
  pointing at the local store instead of mock arrays.
- **Groups:** a new `app/src/groups/tree.ts`, ported from `GroupService`.
  `buildTree`/`buildTypeGroup`/`facetValues`/`isUsable`/`find`/`collect` are
  already pure, static and database-free — around 120 lines of TS.
- **Shared:** `app/src/knowledge/facets.ts`, a port of
  `common/KnowledgeFacets` (the `FACETS` and `DISPLAY_NAMES` maps), consumed by
  both. The Java file already exists precisely because two copies drift; the TS
  side gets one file for the same reason.

**Four things the port must reproduce exactly, because they are load-bearing:**

| Thing | Value | Why it matters |
|---|---|---|
| `MIN_GROUP_SIZE` | `5` | A subgroup of 1 is noise; changing it reshapes the Library |
| `ID_SEPARATOR` | `~` | Group ids are **route params** (`/group/[id]`) — a different id breaks deep links |
| `itemCount` | counts **distinct** saves | The documented bug: `genre` is a list, so summing memberships reported "2 items" over a one-movie library |
| `[unclear]` | never a facet value | A folder named after our own uncertainty |

**The consequence to accept:** deriving locally requires the client to hold the
*whole* library, not page 0. Today `SavesProvider` holds 25 saves. The initial
sync must therefore walk `GET /v1/saves` to exhaustion. At the library sizes
this app will see for a long time that is a handful of 100-row pages; the point
at which it stops being fine is the same point at which the server's own
full-library scan stops being fine, so neither side is newly constrained.
Before the first sync completes, groups and collections show what is local and
fill in — honest, and visibly better than a spinner.

---

## Server changes

### `V15__sync.sql`

```sql
-- Delta cursors. (updated_at, id) so keyset paging cannot loop on ties.
create index saves_user_updated_idx            on saves               (user_id, updated_at, id);
create index save_item_states_user_updated_idx on save_item_states    (user_id, updated_at);
create index entity_states_user_updated_idx    on entity_states       (user_id, updated_at);
create index collection_overrides_user_upd_idx on collection_overrides(user_id, updated_at);

-- space_members has no updated_at at all — only joined_at.
alter table space_members add column updated_at timestamptz not null default now();
create trigger space_members_set_updated_at
  before update on space_members
  for each row execute function set_updated_at();
create index space_members_user_updated_idx on space_members (user_id, updated_at);

-- Deletes are the only thing a delta cannot infer.
create table tombstones (
  id          bigserial primary key,
  user_id     uuid not null references profiles(id) on delete cascade,
  entity_type text not null,   -- space | space_member | comment | vote | shopping_item | collection_override
  entity_id   text not null,
  deleted_at  timestamptz not null default now()
);
create index tombstones_user_deleted_idx on tombstones (user_id, deleted_at, id);
```

`set_updated_at()` already exists (`V1__init.sql:22-28`) and is reused.

### Tombstone write sites (all of them)

| Call site | Tombstone written |
|---|---|
| `SpaceService.java:198` delete space | `space`, **one row per member** — read `space_members` *before* the delete, or the other members never learn it is gone |
| `SpaceService.java:262` remove member | `space_member` for remaining members; `space` for the removed user (their access ended) |
| `SaveSocialService.java:123` delete comment | `comment` |
| `SaveSocialService.java:154` clear vote (value 0) | `vote` |
| `ShoppingListService.java:167, 360, 376` | `shopping_item` |
| `CollectionOverrideService.java:169` unmerge | `collection_override` |

Not a delete, and deliberately not tombstoned: `DuplicateDetector.java:205`
(`update saves set space_id = null`) — the `saves` trigger bumps `updated_at`,
so the ordinary delta already carries it.

⚠️ **One thing to probe, not assume:** `saves.space_id` is
`on delete set null`, so deleting a Space updates save rows via FK cascade.
Postgres *should* fire the row-level `BEFORE UPDATE` trigger for cascaded
updates, which would mean those saves appear in the delta for free. Confirm
with a read-only JDBC probe before relying on it; if it does not fire, the
space-delete path must bump those rows explicitly.

### `GET /v1/sync`

```
GET /v1/sync?since=<iso8601>&sinceId=<uuid>&limit=200
```

```json
{
  "until":   "2026-08-09T12:00:00.123Z",
  "untilId": "…",
  "hasMore": false,
  "saves":        [ SaveResponse… ],
  "spaces":       [ Space… ],
  "spaceMembers": [ { "spaceId": "…", "member": Member } ],
  "itemStates":   [ { "saveId": "…", "itemPath": "exercises[2]", "state": {…} } ],
  "entityStates": [ { "entityKey": "…", "state": {…} } ],
  "overrides":    [ { "overrideType": "…", "subjectKey": "…", "value": "…" } ],
  "deleted":      [ { "type": "space", "id": "…" } ]
}
```

**Watermark semantics, and why each choice:**

- **The client never uses its own clock.** It stores `until`/`untilId` from the
  response and sends them back. Device clock skew is otherwise a silent
  data-loss bug.
- **`until` is the cursor of the last row actually included, not wall-clock
  `now()`.** When a page is capped, wall-clock would skip every row that did
  not fit.
- **Keyset on `(updated_at, id)`.** Two rows written in the same transaction
  share a timestamp to the microsecond; a timestamp-only cursor either loops
  forever or drops one.
- **Apply is upsert-by-id inside one transaction, and the watermark advances
  only on success.** A replayed window is therefore harmless, which is what
  makes a deliberate overlap safe.
- **First sync** is the same endpoint with no `since` — it pages until
  `hasMore` is false. There is no separate bootstrap path to keep correct.

**Honestly delta-syncable, and not:**

| Entity | Delta? | Why |
|---|---|---|
| `saves`, `save_item_states`, `entity_states`, `collection_overrides`, `spaces`, `shopping_list_items` | yes | trigger-maintained `updated_at` |
| `space_members` | yes, after V15 | had no `updated_at` at all |
| `space_activity`, `space_invites`, `save_votes`, `save_duplicates`, `digests` | **no** | no `updated_at`; all low-tier, on-demand reads. Adding one to each is a bigger migration than these screens are worth |
| `save_comments` | has `updated_at`, but **left out** | scoping "comments on saves I can see" is a join across spaces; comments are a detail-screen read, medium tier |

### Generalised idempotency (`V16__idempotency.sql`)

Three writes are not replay-safe — `POST /v1/spaces`, `POST /v1/spaces/{id}/invites`,
`POST /v1/saves/{id}/comments` — and an offline queue that retries them creates
duplicate Spaces and double-posted comments.

```sql
create table idempotency_keys (
  user_id       uuid not null references profiles(id) on delete cascade,
  key           text not null,
  endpoint      text not null,
  status_code   int,
  response_body jsonb,
  created_at    timestamptz not null default now(),
  primary key (user_id, key)
);
```

`IdempotencyService.execute(userId, key, endpoint, Supplier<T>)`: insert a claim
row; on conflict, return the stored response if present, else `409` (the request
is still in flight — the outbox will retry). Applied to the three endpoints
above via the existing `@RequestHeader("Idempotency-Key")` pattern.

**`POST /v1/saves` keeps its existing column-based mechanism.** It is live-verified,
backed by a partial unique index (V2), and migrating it to the new table is
schema risk for no behavioural gain. The inconsistency is deliberate and is
recorded here so the next reader does not "fix" it.

---

## Phases

Ordered so the largest perceived-speed win lands first and each phase ships alone.

### L1 — the store, and Home renders from cache ✅ *landed 2026-08-09*

**Build:** `app/src/local/` — `store.ts` (interface + change bus),
`sqliteStore.ts`, `memoryStore.ts`, `schema.ts`, `useLive.ts`. Add `expo-sqlite`
to `app/package.json` and to `app.json` plugins (`enableFTS`, and
`enableChangeListener: true` when opening). Add store-open to `SplashGate`'s
`ready` in `app/app/_layout.tsx`. Rewrite `app/src/saves/SavesProvider.tsx` to
read from the store and to run a background refresh instead of a blocking fetch.

**Verify:** typecheck; `expo export --platform web`; headless Chrome with
`USE_MOCK_DATA` on — confirm Home paints cards with no spinner on a second
load. Node-standalone tests for the store's pure parts.
**Unverified:** the SQLite path itself (web uses the shim; no device here).

**What actually landed**, plus four things the plan did not spell out:

- **The platform split is a Metro platform extension, not a runtime branch.**
  `sqliteStore.web.ts` returns `null` and `@/local/index` falls through to
  `memoryStore`. Confirmed by grepping the built web bundle: no
  `openDatabaseAsync`, no `expo-sqlite`, no `enableChangeListener` — so the
  alpha WASM path is not merely unused on web, it is not *there*, and the test
  harness is untouched.
- **`enableFTS` was deliberately not set yet.** `expo install` added the bare
  `"expo-sqlite"` plugin entry; FTS5 has no consumer until `searchLocal(q)` in
  L5, and a config-plugin option with no reader is a thing to forget rather
  than a thing to build on.
- **`memoryStore` persists an AsyncStorage snapshot rather than being a pure
  shim.** A session-scoped mock would make "paints from cache on a second load"
  untestable in the one environment this project can drive, so the claim would
  have shipped unexercised. It is what the CDP run below actually measures.
- **Two pieces the plan implies but does not name:** `SyncProvider` (session →
  bootstrap → `syncAll`, and the store-wipe when the signed-in user id differs
  from the one the rows belong to), and `useSync`/`useTaskStatus` — because a
  screen now needs *data* and *liveness* separately, and collapsing them back
  into one `loading` boolean would undo the whole point.

### L2 — every read screen, and derived groups/collections ✅ *landed 2026-08-09*

**Build:** `app/src/knowledge/facets.ts`, `app/src/groups/tree.ts`; point
`app/src/collections/merge.ts` at the store. Migrate `LibraryScreen`,
`SpacesScreen`, `SaveDetailScreen`, `CollectionDetailScreen`,
`GroupDetailScreen`, `SpaceDetailScreen`, `ShoppingListScreen`, `SettingsScreen`
to `useLive`. Delete `SpacesScreen`'s N+1 focus refetch — members come from the
store. Per-type counts become a local `count(*) group by`; no precomputed
counts table, because that query is sub-millisecond over a few thousand rows and
a stored total is a thing that can go stale.

**Verify:** node-standalone on `tree.ts` against fixtures pinning
`MIN_GROUP_SIZE`, the `~` separator, distinct-`itemCount`, and `[unclear]`
exclusion — plus a parity check against `GroupServiceTest`'s own fixtures.
CDP-drive Library → Collections → entity sheet → group navigation.

**What actually landed**, and the decisions the plan left open:

- **The derived views live in `app/src/local/derived.ts`**, not inside each
  screen: `readGroups` / `readGroup` / `readGroupSaves` (from `@/groups/tree`),
  `readCollections` / `readCollectionEntities` (from the existing
  `@/collections/merge`), and `readContinueSaves`. Home's Continue rail was
  `GET /v1/saves/lifecycle` purely because the feed was paged and filtering
  page 0 would miss anything older; holding the whole library removes the
  reason, so it is derived too — one more request the plan did not count.
- **K4's collection overrides are deliberately *not* reproduced locally.** They
  live in `collection_overrides` server-side, no endpoint reads them back, and
  nothing in the app writes one today (merge/rename are endpoint-complete with
  no picker UI; *pin* rides `entity_states`, which does sync). The local tree
  passes `EMPTY_OVERRIDES` and nothing observable changes — stated in
  `derived.ts` so the next reader does not mistake it for an oversight.
- **Entity state is harvested from the collection endpoint, because there is no
  other read path.** `sync.syncEntityStates` calls
  `listCollectionEntities(type)` for the entity-bearing types actually present
  locally and keeps only each entity's `state`. That is three requests once per
  sync, replacing a fetch on every visit to the Library, the collection screen
  and every `recommendation_list` save's detail screen.
- **`WorkoutCompareScreen` was migrated too**, though the plan does not list it:
  it did N × `getSave` for ids the previous screen had just listed *from the
  store*, which was pure latency and broke the comparison entirely offline.
- **Optimistic writes now go through the store, not component state.** Ticking
  a shopping item, marking an entity watched and flipping an item state all
  write locally first, so the same change is already reflected on every other
  screen showing it — and a shopping-list tick survives navigating away, which
  it did not before.
- **`SpaceDetailScreen` keeps activity and duplicates on the network,
  deliberately.** Neither table carries an `updated_at`, so neither is
  delta-syncable (see the table above); caching them would buy a stale feed
  rather than a fast one.

**Verified (L1 + L2 together), 2026-08-09:** `tsc --noEmit` clean, including
under `--noUnusedLocals`; `expo export` clean for both `web` and `android`;
**53 node-standalone assertions** — all twelve `GroupServiceTest` cases replayed
against the shipped `tree.ts` with its own fixtures (including
`countsDistinctSavesNotMemberships`, the one a real database found), plus
`MIN_GROUP_SIZE`'s 4-vs-5 boundary and the store's row mapping, `[unclear]`
title handling, feed ordering with its tie-break, and the `itemStates`
strip/re-attach; and **19 CDP checks** driving the real app on `expo start
--web` with `USE_MOCK_DATA` on (never committed — the pre-commit hook forces it
back). Those confirmed, in order: a cold start with `localStorage` cleared
fills the store with all 12 saves and persists a snapshot carrying **zero**
`itemStates` on any save row; a second load paints the feed, the Continue rail,
the derived AI-groups grid and the Spaces strip within 2.5s **with no spinner
on screen**; tapping a derived group lands on `/group/workout` with its saves;
Library shows the derived Collections section and per-type counts; and the
collection screen shows entities merged across two sources, sectioned by K2
state.

**Still unverified, and it is the same gap as everywhere else app-side:** the
SQLite implementation itself has never run — web resolves the memory shim, and
there is no device or emulator here. Everything above proves the *store
contract* and the screens on top of it, not `sqliteStore.ts`'s SQL.

### L3 — the outbox

**Build:** `app/src/local/outbox.ts` + drain loop in `sync.ts`. Every existing
optimistic-update site (the ~11 in `LibraryScreen`, `SaveDetailScreen`,
`CollectionDetailScreen`, `LifecycleStrip`, `Discussion`, `ShoppingListScreen`,
`AddToSpaceSheet`) stops hand-rolling revert logic and enqueues instead. Send
`Idempotency-Key` on `createSave` — the server has supported it since V2 and the
Android `ShareUploadWorker` already generates one; the app just never did.

Retry policy: exponential backoff (1s→5min cap) on `network`/`server`;
`validation`/`notFound`/`forbidden`/`quota` mark the row `failed` and are
surfaced rather than retried forever; `unauthorized` pauses the queue until the
session refreshes. A `failed` row is skipped, never a permanent head-of-line
block.

**Local→server id reconciliation:** an offline `createSave` inserts a row with
id `local:<uuid>` and `status: 'processing'`. When the POST lands, one
transaction deletes the local row, inserts the real `SaveResponse`, and rewrites
`item_states.save_id` plus any outbox `entity_id`/`payload` referencing the
local id.

**Verify:** node-standalone on the outbox reducer (ordering, backoff,
terminal-vs-retryable classification, id rewrite) — this is the highest-risk
pure logic in the project and it is fully testable without a device.
**Unverified:** real airplane-mode behaviour.

### L4 — delta sync

**Build:** `V15__sync.sql`, tombstone writes at the six call sites,
`api/.../sync/SyncController.java` + `SyncService`, and the client's watermark
+ apply path. `SyncService` assembles the response as a pure function of query
results so it is unit-testable; the controller runs the queries.

Priority tiers, as asked: **critical** (blocks splash) session, prefs, store
open; **high** saves → spaces → item/entity states; **medium** members,
shopping list, `/me`; **low** digest, activity, duplicates, related. Triggers:
app start, `AppState` → active, connectivity regained (`expo-network`'s
`useNetworkState`, preferred over adding NetInfo since the project already leans
on `expo-*`), after any outbox drain, and pull-to-refresh (which forces a full
pull, not a delta).

**Verify:** `./mvnw test` with new `SyncServiceTest` (keyset paging, tie
handling, tombstone inclusion) and tombstone assertions in `SpaceServiceTest`.
Live: read-only JDBC probe for the new indexes and the FK-cascade trigger
question above, then a throwaway-user round trip against real Supabase —
seed saves, `GET /v1/sync`, mutate, `GET /v1/sync?since=`, assert only the
changed row returns; delete a space, assert the tombstone. Throwaway users are
inserted directly into `auth.users`/`auth.identities` — **never** via
`/auth/v1/signup`, which emails a fake address.

### L5 — idempotency, local search, images

**Build:** `V16__idempotency.sql` + `IdempotencyService` + the three endpoints;
`searchLocal(q)` over FTS5 with a web linear-scan fallback; swap
`SaveThumb.tsx`'s React Native `Image` for `expo-image` (`cachePolicy:
'memory-disk'`, `placeholder`, `transition`).

**Search design:** local FTS returns instantly as `match: 'text'`; the server
call fires in parallel and merges, its semantic-only hits marked
`match: 'semantic'` so the existing "related" badge already renders them
correctly. Dedupe by id; server rank wins for shared ids. The FTS body must
follow V11's rule exactly — **scalar leaves only, never JSON keys** ("name" and
"ingredients" would otherwise match every save), `[unclear]` stripped, title
from `coalesce(title, name)` (the `place` trap that has now broken `saveTitle()`,
`search_tsv` and `SpaceService` in turn).

---

## Explicitly not built, and why

- **CRDTs or field-level conflict resolution.** The server stays authoritative
  and every queued write is an absolute set, not a delta — so last-write-wins is
  not a compromise here, it is the actual semantics of every endpoint.
- **WatermelonDB / Realm / PowerSync.** They buy collaborative live queries.
  Spaces are low-frequency and already have their own activity feed; the cost is
  a heavy native dependency and a second data model.
- **Background sync while the app is closed** (`expo-background-task`). The one
  case that matters — capture while backgrounded — is already handled by the
  Android `ShareUploadWorker`.
- **Local semantic search.** Embeddings are `gemini-embedding-001`, 3072→1536
  and normalised client-side; there is no on-device model, and there will not be
  one. Semantic stays server-side and merges in.
- **A precomputed counts table.** `count(*) group by knowledge_type` over a
  local library is sub-millisecond, and a stored total is a thing that goes
  stale.
- **Delta sync for `space_activity` / invites / votes / duplicates / digests.**
  No `updated_at` on any of them, all low-tier, all fine as on-demand reads.
- **Deleting the now-unused `GET /v1/groups` and `GET /v1/collections`.** They
  remain API surface and keep `mockRepository` parity honest; the app simply
  stops calling them.

---

## Open questions (answer by measuring, not by fiat)

1. **Does a Postgres FK `on delete set null` cascade fire the row-level
   `BEFORE UPDATE` trigger?** Decides whether deleting a Space carries its saves
   in the delta for free. One read-only probe answers it.
2. **How long does `expo-sqlite` take to open on a real mid-range Android
   device?** It is being added to `SplashGate`'s blocking set; if it is not the
   cheapest of the four gates, it must move off the critical path.
3. **At what library size does walking `GET /v1/saves` to exhaustion on first
   run stop being acceptable?** Measure once there is a user with more than a
   few hundred saves; the answer decides whether first sync needs its own
   progressive UI.
