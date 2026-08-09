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

> **Status: L1–L5 landed 2026-08-09. The plan is complete.** Every read screen
> reads the local store through `useLive`; groups and collections are derived
> on-device; every write lands locally and drains from an outbox; reads are a
> windowed `GET /v1/sync` rather than a full fetch-and-replace; and search,
> the last read path that could only answer from the network, answers from the
> device first. `@/local/sync` is the only consumer of `repo` in both directions.

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

5. ~~**The server has zero sync primitives.**~~ **It has them as of L4.** This was
   true when the plan was written — a grep across `api/src` for
   `since|updatedAfter|ETag|If-None-Match|Last-Modified|cursor|deleted_at|tombstone|@Version`
   returned no production hits, and a `where updated_at > ?` scan was unindexed. The
   constraint it implied still holds and is worth keeping in mind: **delta sync was
   a migration, not a query-param**, which is why L1/L2 shipped honest full pulls
   rather than pretending.

6. ~~**There are no tombstones, and seven hard-delete paths.**~~ **Six of the seven
   write one now** (`V15`); the seventh is not a delete at all —
   `DuplicateDetector`'s `update saves set space_id = null` rides the ordinary
   delta, confirmed by probe. The reasoning survives: **deletes are the part of sync
   that cannot be inferred**, and the audience has to be read *before* the delete or
   there is nobody left to address.

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

**Two changes from that sketch in what shipped, both stated in the migration:**

- **The trailing `id` on `saves_user_updated_idx` is gone**, because the cursor is
  a timestamp — see the `GET /v1/sync` section below for why the keyset could not
  survive contact with seven tables. An index column nothing orders by is a thing
  to wonder about later.
- **Three indexes the sketch missed:** `spaces (updated_at)` and
  `shopping_list_items (list_id, updated_at)` (both scoped through a join rather
  than by `user_id`), and `space_members (space_id, updated_at)` — the client asks
  for "every member of every Space I am in", which walks by space, where the
  sketch's per-user index serves only "my own membership row".

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

✅ **Probed, not assumed — and the answer is yes.** `saves.space_id` is
`on delete set null`, so deleting a Space updates save rows via FK cascade, and
the question was whether that cascaded UPDATE reaches the row-level
`BEFORE UPDATE` trigger. Measured against the live database with a throwaway
user, space and save: deleting the Space moved the save's `updated_at` from
`13:25:41.403104+00` to `13:25:43.307199+00` and set its `space_id` to null. So
**deleting a Space carries its saves in the ordinary delta for free** and the
space-delete path does not need to bump them. Recorded as a comment on
`DuplicateDetector.merge` too, which relies on the same behaviour for its own
`update saves set space_id = null`. Everything the probe created was torn down
(0 saves, 0 spaces, 0 profiles left for that user id).

This could not have been answered by reading the catalog: the trigger exists
either way, and the only question was whether Postgres routes a cascade through
it.

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

- **The client never uses its own clock.** It stores `until` from the response and
  sends it back. Device clock skew is otherwise a silent data-loss bug.
- **`until` is the cursor of the last row actually included, not wall-clock
  `now()`.** When a page is capped, wall-clock would skip every row that did
  not fit.
- ~~**Keyset on `(updated_at, id)`.**~~ **This is the one thing in the plan that
  could not be built, and the reason is worth reading.** A keyset is the right
  shape for paging *one* table. This endpoint pages **seven** against one shared
  cursor, and four of them have no scalar `id` to break a tie on:
  `save_item_states` is keyed on (save, user, item path), `entity_states` on
  (user, entity key), `collection_overrides` on (user, type, subject) and
  `space_members` on (space, user). A single `untilId` handed back to all seven
  would be meaningless in six of them, and "id > that" would *skip* rows rather
  than resume after them.

  So the cursor is `updated_at` alone, and the hazard it was there to prevent —
  two rows written in one transaction sharing a timestamp to the microsecond — is
  handled by never ending a page *inside* such a group. `SyncWindow` fetches
  `limit + 1` rows per type, and if any type overflows it pulls `until` back to
  the smallest of the overflowing types' `limit`-th timestamps and re-reads every
  type with an inclusive `<= until` bound and no limit. A capped page can
  therefore exceed `limit` by the size of one timestamp group, which is a handful
  of rows from one transaction — and `until > since` strictly, so a client always
  makes progress even in the degenerate case where a single group is larger than
  the page size. `untilId` is **not** in the response: a field that is always null
  is a thing to forget rather than a thing to build on.
- **Apply is upsert-by-id and the watermark advances only on success.** A
  replayed window is therefore harmless, which is what makes the deliberate
  inclusiveness above safe.
- **First sync** is the same endpoint with no `since` — it pages until
  `hasMore` is false. There is no separate bootstrap path to keep correct.

**Honestly delta-syncable, and not:**

| Entity | Delta? | Why |
|---|---|---|
| `saves`, `save_item_states`, `entity_states`, `collection_overrides`, `spaces`, `shopping_list_items` | yes | trigger-maintained `updated_at` |
| `space_members` | yes, after V15 | had no `updated_at` at all |
| `space_activity`, `space_invites`, `save_votes`, `save_duplicates`, `digests` | **no** | no `updated_at`; all low-tier, on-demand reads. Adding one to each is a bigger migration than these screens are worth |
| `save_comments` | has `updated_at`, but **left out** | scoping "comments on saves I can see" is a join across spaces; comments are a detail-screen read, medium tier |

### Generalised idempotency (`V16__idempotency.sql`) ✅ *landed 2026-08-09*

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

`IdempotencyService.execute(userId, key, endpoint, Class<T>, Supplier<T>)`:
insert a claim row; on conflict, return the stored response if present, else
`409` (the request is still in flight — the outbox will retry). Applied to the
three endpoints above via the existing `@RequestHeader("Idempotency-Key")`
pattern.

**`POST /v1/saves` keeps its existing column-based mechanism.** It is live-verified,
backed by a partial unique index (V2), and migrating it to the new table is
schema risk for no behavioural gain. The inconsistency is deliberate and is
recorded here so the next reader does not "fix" it.

**Three things the sketch left open, decided while building it:**

- **The claim, the work and the response write are all in the caller's
  transaction**, not a `REQUIRES_NEW` claim followed by the work. That is what
  makes a failure clean: the work rolls back and so does the claim, so a retry
  genuinely retries instead of being told forever that a request which never
  happened is in flight. The concurrency this produces is *better* than the
  alternative rather than a cost of it — a second request carrying the same key
  blocks inside `on conflict do nothing`, and when the first commits its insert
  affects no rows and its follow-up `select` (READ COMMITTED takes a fresh
  snapshot per statement) sees the stored response and replays it. When the
  first rolls back, the second's insert succeeds and it does the work. Both are
  the right answer, with no polling and no retry loop. The upshot is that the
  planned **409 is now unreachable through the ordinary flow** — it is kept for
  a claim row with no response, which is what a hand-inserted row or a future
  `REQUIRES_NEW` claim would produce.
- **The stored response is the deliverable, not the avoided write.** Recording
  only that a key was seen and answering `204` would leave a retried invite
  creation "successful" and the caller still without the code the request exists
  to produce.
- **`endpoint` is stored but is not part of the key.** A key reused across two
  endpoints is a client bug, and it is reported as one (`400`, which the outbox
  treats as terminal and surfaces) rather than quietly running both — without
  the column the mistake would instead deserialise one endpoint's stored body
  into another's record and fail somewhere far from the cause.

**Client side, only `addComment` sends a key so far, and that asymmetry is
deliberate.** It is the one queued op that *creates* a row — every other one is
an absolute set, so replaying it is a no-op by construction — and the outbox has
generated a key per entry since L3 with nowhere to send it. `createSpace` sends
one too, minted per *subject* rather than per sheet: `CreateSpaceSheet` stays
open on failure so the user can tap Create again, which is exactly the
lost-response case, but a key stable for the whole sheet would replay the old
attempt after the user edited the name and hand back a Space called something
else. `createInvite` deliberately sends none: two taps of "Invite" are genuinely
ambiguous between "the first did not register" and "I want a second link", and
the server-side half is in place for the day that write is queued, where one
entry with one key makes the answer unambiguous.

✅ **`V16__idempotency.sql` executed against the live schema inside a transaction
and rolled back**, same technique as V15 and for the same reason — 20 checks:
every column and its type, the composite primary key, RLS and its policy, the FK
cascading from `profiles`, all three statements `IdempotencyService` issues
accepted against the new shape (including a repeated claim affecting **zero**
rows, which is the entire mechanism), the stored body round-tripping as JSON
text, and `explain` confirming the replay read uses `idempotency_keys_pkey`.
Then rolled back, and confirmed afterwards that the table does not exist.

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

### L3 — the outbox ✅ *landed 2026-08-09*

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

**What actually landed**, plus six things the plan did not spell out:

- **`outbox.ts` has no runtime imports at all**, deliberately. The ordering, the
  backoff, the terminal-vs-retryable split and the id rewrite are the highest-risk
  logic in this layer, and keeping the module pure is what lets it be *executed*
  rather than only typechecked. The store-facing half is `sync.ts` (queue and
  drain) and `writes.ts` (what screens call).
- **Screens call `@/local/writes`, not the outbox.** One function per action, each
  doing exactly two things — write the new value to the store, queue the request —
  and **no revert branch anywhere**. That absence is the deliverable: a tick undone
  by a failed request is indistinguishable from a tap the app never registered, so
  the old rollback was actively worse than doing nothing.
- **A `failed` write is *surfaced*, and discarding it re-reads the server.**
  `useOutbox` + `PendingWrites` (on Settings) report rejected writes with a retry
  and a discard. Retry clears the terminal status and keeps the entry's position
  *and its idempotency key*; discard drops the row **and** pulls the affected save
  (or re-harvests entity state, or re-fetches the shopping list), because dropping
  alone leaves the store showing a value nobody will ever agree with.
- **Ordering is per entity, not global.** `selectNext` skips `failed` rows
  entirely and lets a backed-off entry block only later entries with the *same*
  `entityId`. Two flag changes to one save must land in order; a tick on a
  different save must not wait out the first one's backoff. A `createSave` that
  fails terminally cascade-fails its dependents, since a save that will never exist
  server-side cannot be flagged or filed there either.
- **`putSaves({ replaceAll: true })` must never reap a `local:` row**, in both
  store implementations. The server has never heard of such a save, so a full pull
  cannot mention it — reaping it would delete the user's save *and* orphan the
  outbox entry that creates it. This is the sharpest interaction between L3 and the
  reaping refresh, and it is silent if got wrong.
- **`SavesProvider`'s processing-poll is now feed-driven, and `prepend` is gone.**
  The old timer started inside `prepend`, so only the screen that created a save
  ever watched it: a save still processing when the app closed sat at
  "Processing…" forever, and a save made on another device never advanced at all.
  Reading the condition off the store covers every case with less code — and
  `local:` saves are excluded, becoming pollable the moment the outbox reconciles
  them onto a real id.
- **Two writes deliberately stayed off the queue:** `createSpace` and
  `acceptInvite`. Both hand back a Space the user is immediately navigated into, so
  there is nothing useful to do with them offline — and both are exactly what L5's
  `V16__idempotency.sql` exists for. Queueing them before that migration lands is
  the one way to create duplicate Spaces.

**Verified:** 45 node-standalone assertions over `outbox.ts` and `store.ts`'s pure
halves — every disposition, the backoff curve and its cap, `selectNext`'s
skip-failed and block-same-entity rules, the wake timer, local-id shape, the
two-halves id rewrite (including a no-op returning the *same object*), and the
tombstone composite-id split against an entity key containing both `:` and `|`.
Then driven for real through headless Chrome with `USE_MOCK_DATA` on: **11 checks
on the retry path** against a mock patched to fail twice with a `network` error —
the local value changed at once, the entry stayed queued with `attempts=1` and a
scheduled backoff, the third attempt succeeded, the queue drained, and the
idempotency key was never regenerated — and **11 checks on the terminal path**
against a mock returning 403: not rolled back, marked `failed` with no
`nextAttemptAt`, the server's own message kept, reported on Settings with both
actions, and Discard re-converging the store on the server's value and then hiding
the section. Both mock patches were reverted and the file confirmed byte-identical
to its backup afterwards.
**Unverified:** real airplane-mode behaviour, and the `expo-network` listener
(there is no device here to change networks on).

### L4 — delta sync ✅ *landed 2026-08-09*

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

**What actually landed**, and where it diverges:

- **The risky part is pure and the queries are not.** The plan's split ("assembles
  the response as a pure function… the controller runs the queries") turned out to
  be the wrong seam: assembling is trivial, and *where a page ends* is the only
  thing that can silently lose a row. So `SyncWindow.cap` is static, dependency-
  free and fully unit-tested, `SyncService` runs the queries and calls it, and the
  controller does nothing but parse `since`.
- **The high tier collapsed into one request.** The plan's "saves → spaces →
  item/entity states" ordering is moot: they arrive together. `syncAll` is now
  *drain the outbox, then one delta*, plus the three medium/low tasks. The drain
  goes first on purpose — a queued write must reach the server before a pull tells
  the app what the server currently thinks, or the pull overwrites the local value
  with a stale one and the drain then puts it back, visible as a flicker.
- **Three requests per sync disappeared with it.** `syncEntityStates` existed only
  because entity state had no read path but the collection endpoint; the delta
  carries it directly. The task stays for the reaping refresh and the discard path.
- **`spaceMembers` sends a Space's *whole* member list**, not the changed rows,
  whenever any member row changed. One changed role is rare and a member list is
  small — and it lets the client apply it with the replace-per-space write it
  already had, instead of a merge rule with a new store method behind it.
- **`saves` is scoped to `user_id` only**, exactly what `GET /v1/saves` returns —
  not "own plus every Space I am in". A delta that widened visibility would not be
  a drop-in replacement for the full pull it stands in for, and it would need a
  tombstone for "this save left a Space you share" that this endpoint does not
  write. Space saves keep arriving from `GET /v1/spaces/{id}/saves` on demand.
- **The shopping list stayed a full pull, for ordering rather than cost.** The
  client stores it in the server's aisle-then-name order so a shopper walks the
  shop once, and a stream of item rows carries no position to rebuild that from.
  Its *deletions* do ride the tombstone list — the half a full pull handles worst.
- **`comment` and `vote` tombstones are written with no consumer**, deliberately
  and stated at both ends. Neither table is cached client-side, and `applyDeletion`
  ignores types it does not know — which is also what keeps an older client working
  against a newer server. The alternative was a deletion record that is complete
  for five of seven paths, which is worse than one that is complete and partly
  unused.
- **K4's collection overrides are now local**, reversing L2's note. That note gave
  a reason — "there is no endpoint that reads them back" — and `GET /v1/sync` is
  that endpoint, so the local tree can finally agree with the server's about a
  renamed or manually merged entity. Chains are resolved server-side before
  sending, so what arrives is already `A -> C`.
- **`SCHEMA_VERSION` went to 2, and the bump is no longer free.** Every earlier
  table was a cache of something the server can re-send; `outbox` is the first that
  holds writes the server has never seen. Dropping it is accepted once (there is no
  outbox in the field at the moment of the bump) and every future bump has to weigh
  it rather than assume the old rule still holds.

**Verified:** full backend suite green, **408/408, 6 opt-in skipped** — 15 new
tests: `SyncWindowTest` (8, including the earliest-boundary rule and the
all-one-timestamp degenerate case), `TombstoneServiceTest` (3), and
`SpaceServiceTombstoneTest` (4, one of which pins the *ordering* — the audience
must be read before the delete, because `space_members` cascades away with the
Space and a tombstone written afterwards has nobody to address). App: `tsc
--noEmit` clean including under `--noUnusedLocals`, `expo export` clean for web and
android, and the web bundle still contains no `openDatabaseAsync`, no
`expo-sqlite`, no `enableChangeListener` — L1's guarantee survived L3/L4 touching
every write path.

**Live against the real database**, two probes, both cleaning up after themselves:

- **The FK-cascade question above, answered** (see that section).
- **The whole of `V15__sync.sql` executed against the live schema inside a
  transaction and rolled back** — because the SQL had never run, and a migration
  that fails halfway leaves Flyway needing manual repair. All 14 objects came into
  being (table, column, trigger, 8 indexes, RLS, policy), all 9 queries
  `SyncService` actually issues were accepted against the new shape, and `explain`
  confirmed the new index is *used* (`Index Scan using saves_user_updated_idx`)
  rather than decoration. Then rolled back, so Flyway applies it for real on the
  next boot with nothing already half-there.

**And driven end to end** through headless Chrome on `expo start --web`: **17
checks** covering a cold start filling the store from the delta rather than the
paged walk (`synced_at:delta` set, `synced_at:saves` absent), the watermark being a
server timestamp, the schema bump, no stored save row carrying `itemStates`, a
second load painting inside 2.5s with **no spinner**, a real tap writing through
the queue and drained clean, and the value surviving a cold start undisturbed by
the delta that runs on it.

**Not verified, and it is the same gap as everywhere else:** `GET /v1/sync` has
never been called over HTTP. `SyncService`'s queries are proven to be accepted by
Postgres and its windowing is unit-tested, but the endpoint has not been exercised
by a throwaway-user round trip the way K1/K2 were, and no device has run any of
this.

### L5 — idempotency, local search, images ✅ *landed 2026-08-09*

**Build:** `V16__idempotency.sql` + `IdempotencyService` + the three endpoints;
`searchLocal(q)` over FTS5 with a web linear-scan fallback; swap
`SaveThumb.tsx`'s React Native `Image` for `expo-image` (`cachePolicy:
'memory-disk'`, `transition`).

**Search design:** local FTS returns instantly as `match: 'text'`; the server
call fires in parallel and merges, its semantic-only hits marked
`match: 'semantic'` so the existing "related" badge already renders them
correctly. Dedupe by id; server rank wins for shared ids. The FTS body must
follow V11's rule exactly — **scalar leaves only, never JSON keys** ("name" and
"ingredients" would otherwise match every save), `[unclear]` stripped, title
from `coalesce(title, name)` (the `place` trap that has now broken `saveTitle()`,
`search_tsv` and `SpaceService` in turn).

**What actually landed**, plus seven things the plan did not spell out:

- **The search index cost no `SCHEMA_VERSION` bump, and refusing one is the
  decision.** L4 left a warning — `outbox` is the first local table holding
  writes the server has never seen, so a bump is no longer free — and this was
  the first change to meet it. Bumping would have dropped a real outbox to
  install a table whose entire contents are a pure function of the `saves` rows
  sitting next to it, so `saves_fts` is **rebuilt** at open instead, triggered by
  a count mismatch. The rule that falls out and is now written in `schema.ts`:
  bump only for a change the local data cannot survive, never for one it can
  recompute.
- **The FTS table is created outside `SCHEMA_SQL` and allowed to fail.** FTS5 is
  a compile-time SQLite option; had `create virtual table` been part of the main
  schema batch, a build without it would have taken the whole store down, so
  `openStore()` would fall back to the memory shim and a device would silently
  lose its **entire local database to a missing search index**. Kept apart, the
  worst case is that `searchLocal` runs the same linear scan the web build does.
  `app.json` now asks for `enableFTS` explicitly — L1 left it unset on the
  grounds that "a config-plugin option with no reader is a thing to forget", and
  it has a reader now.
- **What is findable is one definition, shared by both implementations.**
  `searchText` / `searchTerms` / `scoreSearch` / `ftsMatchQuery` live in
  `store.ts` and are pure, so the FTS5 half and the scan half differ only in
  *ranking* (bm25 against a hand-rolled score), never in what they match. That is
  also what let the whole rule set be executed under node rather than only
  typechecked.
- **Every local term is a prefix, where the server prefixes only the last.**
  `fullTextCandidates` extends one term because widening a server-side `tsquery`
  costs a scan; locally it costs a string comparison, and matching `chick past`
  against "chicken pasta" *while the user is still typing* is the entire reason
  the local half exists. The local result set is therefore a superset of the
  server's, never a different one — which is what makes appending local-only
  hits safe.
- **The local half is not debounced at all.** The 350ms wait exists because a
  server search spends an embedding call; an index read on the device has
  nothing to be economical with, so making it wait would give away the only
  advantage it has. Measured through CDP: a hit renders inside 180ms, before the
  server has been asked.
- **A failed server search leaves the local results on screen and says so.** The
  old screen replaced everything with an error card, which offline meant "you
  have nothing" — the exact failure this layer exists to remove. The error card
  now appears only when there is genuinely nothing to show; otherwise a line
  above the list says the semantic half is missing and offers a retry. The
  merge's rule is the same one in code: a server response must never *remove* a
  result the user could already see.
- **One honest gap against the server, stated rather than papered over:** weight
  B of `search_tsv` includes `raw_caption`, which `SaveResponse` does not carry,
  so a phrase that appears only in the original caption is findable on the
  server and not on the device. The merge is what covers it.

**Verified:** backend suite green (**416/416**, 6 opt-in skipped — 8 new
`IdempotencyServiceTest` cases covering no-key passthrough, the claim/store path,
a replay that returns the *first* answer and never re-runs the work, both
conflict shapes, the reused-key rejection, and a failed work unit storing
nothing). App: `tsc --noEmit` clean including under `--noUnusedLocals` (only the
pre-existing unused-`React` imports), `expo export` clean for web and android,
and the web bundle still contains no `openDatabaseAsync`, no `expo-sqlite`, no
`enableChangeListener` — L1's guarantee survived L5 adding a SQL-only feature.
**39 node-standalone assertions** over `store.ts`'s search half and
`search/merge.ts`, driven with real registry shapes: keys never indexed for
`recipe`/`workout` (`quantity`, `sets`, `rest`), `[unclear]` stripped, `place`
titled from `name`, FTS5 operators unable to survive `searchTerms`, terms ANDed,
and the merge's three rules including "no local hit is lost".

**And driven end to end** through headless Chrome on `expo start --web` with
`USE_MOCK_DATA` flipped on locally (never committed — the pre-commit hook forces
it back): **23 checks**, including a local hit rendered inside the pre-debounce
window, a prefix match, a value nested inside an object array, a schema key
matching nothing locally, an FTS5-operator query not erroring, and — against
`mockRepository.searchSaves` patched to reject with a `network` error — local
results surviving the failure, the fallback line stated rather than silent, no
error card replacing a usable list, and the error still shown when there is
nothing local either. The patch was reverted and the file confirmed
byte-identical to its backup afterwards.

**Unverified, and it is the same gap as everywhere else:** `sqliteStore.ts` has
still never run, so the FTS5 `MATCH`, the bm25 ordering and the rebuild-on-count-
mismatch are typechecked and reasoned about, not executed — web resolves the
memory shim and there is no device here. `expo-image`'s disk cache is in the same
position: it is a native module doing native caching, and nothing here can watch
it work.

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

1. ~~**Does a Postgres FK `on delete set null` cascade fire the row-level
   `BEFORE UPDATE` trigger?**~~ **Answered 2026-08-09: yes.** Measured against the
   live database — see the tombstone section. Deleting a Space carries its saves in
   the ordinary delta for free.
2. **How long does `expo-sqlite` take to open on a real mid-range Android
   device?** It is in `SplashGate`'s blocking set; if it is not the cheapest of the
   four gates, it must move off the critical path.
3. **At what library size does walking `GET /v1/saves` to exhaustion stop being
   acceptable?** Less pressing than it was — the ordinary path is now the delta, and
   the walk only runs on pull-to-refresh. The number still matters for the *first*
   sync, which pages `GET /v1/sync` to exhaustion instead; measure once there is a
   user with more than a few hundred saves.
4. **Does the tombstone table need a retention policy, and what is it?** Nothing
   prunes it. Growth is slow (deletes are rare, and there is no delete-a-save path
   at all), but the answer depends on how long a client may stay offline and still
   be trusted to hold a consistent cache — a decision, not a measurement, and one
   that has to be made before the table is big enough to matter.
5. **Is the outbox's `MAX_DRAIN_STEPS = 100` per drain the right bound?** It exists
   so a queue that keeps refilling cannot spin forever. Untested against a real
   backlog, because there has never been one.
6. **Does the FTS rebuild's count-mismatch trigger ever fire on every launch?** It
   compares `count(*)` over `saves_fts` against ready `saves`, and a ready save
   with no indexable text at all — every field `[unclear]`, no title — is
   legitimately absent from the index, so the two would disagree permanently and
   the rebuild would run once per open. Bounded by `REBUILD_LIMIT` and cheap at
   these sizes, but it wants a device to measure on before it is called fine. An
   exact answer (store the indexed count in `kv`) is one line; it is not written
   because a count that can drift from reality is exactly what the comparison
   exists to catch.
7. **Is `expo-image`'s disk cache actually doing anything?** It is a native module
   doing native caching and nothing in this environment can watch it work. The
   claim to check on a device is the second cold start: thumbnails should paint
   with no network at all.
