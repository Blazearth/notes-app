# Knowledge collections: from one-object-per-source to one-object-per-topic

This is the implementation plan for making the Library knowledge-centric. The
problem it solves, stated once: **the pipeline creates one object per *source*,
but users think in one object per *topic*.** Three "top romance anime" Reels
become three isolated `recommendation_list` saves — "Romance Anime #1/#2/#3" —
when what the user is actually doing is building *one* anime watchlist. Same
for three push-day videos: three workout cards, not a growing push program.
Today's Library is AI-generated bookmarks; the goal is an AI-native knowledge
base where sources are provenance and the primary object is the merged,
living collection.

The target hierarchy:

```
Capture → Source (save) → Knowledge object (extraction) → Entity (merged across sources)
                                                              → Collection → Library
```

The source never disappears — "where did Blue Box come from?" opens the
original Reel. It just stops being the thing the Library is made of.

---

## What the vision cannot mean here (read this before the phases)

Every constraint below was earned live, not guessed (see CLAUDE.md for the
incidents). The design in this doc exists to deliver the vision *inside* them.

1. **Merging must never mutate a save.** `structured_data` is immutable after
   extraction, and that immutability is load-bearing: Phase 4's item-state
   identity is the array index (`items[3]` today is `items[3]` forever), the
   embed job dedupes on save id so a changed save silently never re-embeds,
   and every dual-shape consumer assumes old saves keep their shape. "Merge
   instead of creating another object" therefore means **a derived layer above
   saves**, never rewriting an existing save's extraction. Saves stay exactly
   what they are; collections are computed over them.

2. **No generation call may be spent on merging.** Ground rule 1 of
   [next-phases.md](next-phases.md) stands: one Gemini call per save, ever.
   "The AI should detect the existing collection and merge" cannot be a model
   call at save time — 500 RPD is the whole app's capacity, and the Flash pool
   is 20/day. Merge detection and merge execution must come from the three
   legal cost classes: same-call tokens, deterministic local compute, and the
   existing enrichment/embedding machinery (embeddings are a separate 1000 RPD
   pool). This turns out to be enough — see "Entity resolution" below — because
   the classify call already extracted the names, kinds, and facets that
   merging keys on.

3. **Derive, don't store, anything that can go stale.** `GroupService` is the
   precedent and it is the same problem one level up: a stored collection tree
   needs invalidating on every classify, enrich, and delete, and the failure
   mode of missing one is a watchlist that lies about its contents ("27 anime"
   over a library holding 24). Collections are **views, recomputed per
   request**, exactly like groups. The only stored state is what *cannot* be
   derived: per-entity user state (watched, rating) and explicit user
   curation (a rename, a manual merge/split). The `itemCount` lesson applies
   verbatim: a collection counts **distinct entities, not memberships** — Blue
   Box recommended in three Reels is one entry with three sources, not three
   entries and not "count 3".

4. **Aggregation and synthesis are different operations, and only one is free.**
   - *Aggregation* — union + dedupe of entities across sources (a watchlist, a
     Japan guide's places, a checklist union) — is deterministic local compute
     over already-extracted fields. Zero AI. This doc builds it.
   - *Synthesis* — writing a *new* program from three creators' push days ("AI
     continuously improves the program") — is generative. It cannot happen per
     save, per the budget. If it ever exists it is a digest-shaped feature:
     generated only when someone is looking, cached, with its own budget line
     in CLAUDE.md's request-budget section. Phase K5 states the constraint and
     deliberately designs nothing else.

---

## The model

Three nouns, one of them new:

| Noun | What it is | Stored? |
|---|---|---|
| **Source** | A save, exactly as today. Immutable extraction + provenance (URL, thumbnail, savedAt). | Yes — `saves`, unchanged |
| **Entity** | A thing the user is collecting: one anime, one place, one task. Appears in ≥1 sources. Identified by a deterministic **entity key**. | No — derived at read; only its *user state* and *curation overrides* are stored |
| **Collection** | Today's derived group, upgraded: instead of listing saves, it lists merged entities, each carrying its sources. | No — derived at read, same as `GroupService` |

### Which types merge, and how — three shapes, not one rule

1. **Item-bearing list types** — `recommendation_list`, `itinerary`,
   `checklist`. The save's *items* are the entities; merging unions items
   across saves of the same collection. Three romance-anime Reels → one
   watchlist of ~27 distinct anime, each entity holding every source's
   `reason` and `rank`. **This is the flagship shape and the one Phases K1–K3
   build.**
2. **Save-is-the-entity types** — `movie`, `book`, `place`, `product`,
   `recipe`, `github_repo`. The save's own `title`/`name` *is* the entity key.
   Merging here is duplicate detection: two Reels about the same restaurant
   become one entity with two sources. Bonus that falls out of a shared key
   space: a `movie` save of Blue Box and a watchlist *item* named Blue Box
   resolve to the **same entity**, so saving a full review of something
   already on the watchlist enriches the entry (source count +1, review
   attached) instead of creating a sibling — exactly the "Blue Box already
   exists" behaviour the vision asks for. Lands in K4; K1–K3 don't need it.
3. **Synthesis types** — `workout`, `course`. Exercises across creators are
   not "the same entity" in any deterministic sense, and a merged program is a
   new creative artifact. These types keep their per-source objects and
   grouped presentation; the free upgrade available to them is *side-by-side
   compare* (local compute), not merge. AI synthesis is K5-or-never.

### The entity key (deterministic, pure, testable)

```
entityKey = kindNamespace + ":" + normalize(name)
normalize: Unicode NFKC → casefold → trim → collapse whitespace
           → strip surrounding punctuation → strip a leading article (the/a/an)
```

`kindNamespace` is the item's `kind`/`medium` coarsened to a small closed set
(`screen` for film/tv/anime/series, `book`, `game`, `place`, `product`,
`task`, …) so "Blue Box (anime)" and "Blue Box (series)" collide as intended,
while a game and a film sharing a title don't. The function lives as a static,
database-free method (`Entities.key(...)`) for the same reason
`ShoppingListService.fold` is static: the merge property must be unit-testable
without a database.

**Known limit, stated up front:** normalization does not resolve aliases —
"Shingeki no Kyojin" ≠ "Attack on Titan" under any string rule. The answers,
in cost order: (a) canonical ids from enrichment (K4 — when
`RecommendationListEnricher`'s TMDB match succeeds, store `tmdbId` alongside
`posterUrl`; two aliases that both matched get the same id, which then
*overrides* the string key); (b) a stored manual-merge override (K4 — the
user's escape hatch, same philosophy as the Spaces duplicate-merge prompt);
(c) embedding-nearest-neighbour fuzzy matching — **not built until measured**,
because the 0.40 similarity cutoff is provisional on seven queries and a wrong
auto-merge is worse than a visible duplicate. A duplicate the user can merge
by hand is honest; a silent wrong merge corrupts the watchlist.

### What merging produces per entity

- `name` — the most common surface form across sources (ties: earliest save).
- `kind`, `year`, `genre`, `platform` — first non-`[unclear]` value wins;
  `genre` is the union. Never invented: if every source said `[unclear]`, the
  merged value is `[unclear]`.
- `sources[]` — `{saveId, reason, rank, savedAt}` per source. **Reasons are
  never merged into one blob** — "best enemies-to-lovers arc" attributed to
  Reel A and "underrated gem" to Reel B is provenance; a concatenated
  paragraph is neither source's claim.
- `sourceCount` — the "confidence increases" signal from the vision, honestly:
  it is literally the number of independent recommendations, displayed as
  such ("recommended in 3 saves"), not a synthetic confidence score.
- `posterUrl` — first present value (enrichment already adds these per item).
- `state` — the user's entity state (below), joined in at read.

---

## Stored state (the only tables this feature adds)

### `V13__entity_states.sql`

```sql
create table entity_states (
    user_id     uuid not null references profiles (user_id),
    entity_key  text not null,          -- Entities.key output
    state       jsonb not null,         -- {"done": true, "rating": 4, "status": "watching"}
    updated_at  timestamptz not null default now(),
    primary key (user_id, entity_key)
);
```

- **Why entity-keyed and not save-item-keyed:** marking Blue Box watched must
  survive Blue Box appearing in a fourth Reel tomorrow. Phase 4's
  `save_item_states` ties watched-ness to `items[3]` *of one save*; the same
  anime in another save starts unwatched. Entity state is the fix, and it is
  also what makes the collection's "12 watched / 8 remaining" rollup
  derivable.
- **Write is a full replace** (`on conflict … do update set state =
  excluded.state`) — the shopping-list/digest/item-state rule, unchanged.
- **Migration path from Phase 4 item states — dual-read, no backfill.** For
  entity-bearing types the client writes entity state from now on and reads
  `entity_state ?? item_state` (entity wins). Old ticks stay visible through
  the fallback; no migration rewrites them (there is no reliable reverse map
  from `items[3]` to an entity key without re-deriving, and a wrong backfill
  is worse than a stale tick). `workout` exercise completion **stays on
  `save_item_states`** — completing a set in Jeff Nippard's session is a
  per-session fact, not a property of "bench press" the entity.
- Per-user, not per-space, same reasoning as `save_votes`: two Space members
  work through a shared watchlist independently.

### `V14__collection_overrides.sql` (K4, not before)

One table for explicit user curation — the only other thing that can't be
derived: `(user_id, override_type, subject_key, payload jsonb)` covering
collection rename, entity manual-merge (`payload: {into: entityKey}`),
merge-undo/split, pin. Applied as a post-processing step over the derived
view. Kept out of K1–K3 deliberately: ship the derived view first and let real
duplicates tell us how much curation is actually needed.

---

## API surface

New `collection/` module beside `group/`, sharing `GroupService`'s `FACETS`
map (extract it to a shared constant rather than duplicating — the facet that
files a save into a group is the same facet that files it into a collection;
two copies will drift).

- `GET /v1/collections` — the Library's new spine. Derived per request:
  type → facet → `{name, entityCount, doneCount, sourceCount}`. Entity counts
  are distinct-entity counts (constraint 3). Cheap for the same reason
  `GET /v1/groups` is: one read of the user's ready saves, all merging in
  memory; when that stops scaling the answer is an aggregate query, not a
  cached tree.
- `GET /v1/collections/{type}?facet=…` — the merged entity list described
  above, each entity with sources and state joined in. Authorization: the
  caller's own saves only (viewer-scoped, the `relatedTo` rule — a Space
  co-member's private saves must never leak into a collection).
- `PATCH /v1/entity-state` — `{entityKey, state}`, mirroring
  `PATCH /v1/saves/{id}/item-state`'s body-over-path choice (entity keys need
  URL-encoding as path segments for the same reason `exercises[2]` did).
  Access check: none beyond auth — entity state is keyed to the caller and
  references no row by FK, so there is nothing to leak or orphan.
- `GET /v1/saves/{id}` gains nothing; the save detail screen stays
  source-centric. The *entity* detail (sources, merged reasons) is served by
  the collection endpoint's per-entity payload.

---

## Phases

Ordered so each lands alone and the first user-visible win (the anime
watchlist) arrives before any speculative machinery.

### K0 — measure before building (a spike, not a feature) — done, 2026-08-07

Ran as a read-only JDBC probe against the live dev database rather than curl
against a booted server (cheaper, and it costs nothing against the Gemini
budget either way) — pulled every `recommendation_list` save and ran the
normalize spec over their items offline. Found exactly 2 real saves, both
from the same day, 7 items total, **zero overlapping titles**. Every item
produced a distinct key under the spec below, including a real case where
leading-article stripping mattered ("The Second Prettiest Girl in My Class",
"The Fragrant Flower Blooms With Dignity") — a measured **zero-false-merges**
result. It could not measure true-duplicate detection or the alias-miss rate,
because nothing in the sample repeats, and zero `checklist`/`itinerary`
saves exist at all yet, so the two open questions at the bottom of this doc
are still open. **Exit criterion met by default rather than by strong
evidence**: the spec below is frozen as written because nothing contradicted
it, not because a rich duplicate-laden sample validated it — and no saves
were manufactured to force a better one, since that would have spent shared
Gemini budget on a decision this pass wasn't asked to make alone. Revisit
once real `checklist`/`itinerary` saves or a real duplicate exists.

### K1 — the derived merge, server-side — done, 2026-08-07

`collection/` module landed: `Entities.key` (pure static), `CollectionService`
(derives collections + merged entities from ready saves — only shape 1 wired,
exactly as scoped; shape 2 waits for K4), the two GET endpoints
(`GET /v1/collections`, `GET /v1/collections/{type}?facet=`). No migration, no
app change, as planned. `GroupService`'s `FACETS`/`DISPLAY_NAMES` moved to a
shared `common/KnowledgeFacets` rather than being duplicated, per this
section's own instruction above.

- Tests mirror `GroupServiceTest` (`CollectionServiceTest`, `EntitiesTest`):
  hand-built saves, single- and multi-valued facets, and — pinned as a named
  regression, since it is the constraint-3 trap — one entity in three sources
  counts once, and a collection's `entityCount` equals the length of its
  merged list (the exit criterion above, literally, as its own test).
- Merge precedence rules (first non-`[unclear]`, genre union, reason
  attribution) each have a test, and are implemented as one generic
  field-rollup function rather than per-field-name code — a new item field on
  any of the three wired types needs no service change.
- **Verified live**: a throwaway user (see the repo's standing rule on
  minting one — never through `/auth/v1/signup`) with two saves seeded
  directly into `saves` (status `ready`, no pipeline run, zero Gemini cost)
  sharing a "Blue Box" item. `GET /v1/collections/recommendation_list`
  returned it merged — `sourceCount: 2`, `genre` unioned, `year` resolved to
  the one non-`[unclear]` source, each source's own `reason` kept unblended —
  and `GET /v1/collections` showed the matching tree. Full backend suite
  green (365/365). Throwaway user and both seeded saves deleted afterward.

### K2 — entity state — done, 2026-08-07

`V13__entity_states.sql` (`user_id, entity_key` composite PK, same
`set_updated_at` trigger + RLS-own-policy shape as V12), `EntityStateService`
(`JdbcClient`, `ShoppingListService`/`SaveItemStateService` style — a batched
`statesFor(userId, Collection<String>)` read plus a full-replace upsert, no
access check beyond auth since an entity key references no row by FK),
`PATCH /v1/entity-state` on a dedicated `EntityStateController` (not a method
on `CollectionController` — that controller's own `@RequestMapping` would have
forced the path under `/v1/collections/entity-state`, not the spec's
top-level `/v1/entity-state`). `CollectionEntity` gained a `state` field and
`CollectionNode` a `doneCount` field (0 from the pure builder, filled in by a
second pass — `CollectionNode.withDoneCount` — once `CollectionService`'s
instance methods have loaded state; the pure `mergeType`/`buildTree` stay
database-free, unchanged).

App-side: `@/collections/entities.ts` and `@/collections/merge.ts` are hand-
kept TypeScript ports of `Entities.key` and `CollectionService`'s merge core.
Two different reasons drove them, not one — `merge.ts` lets `mockRepository`
derive real `GET /v1/collections`-shaped data from `MOCK_SAVES` (the same way
the server derives it from `saves`) instead of a hand-authored, un-mergeable
fixture tree like `MOCK_GROUPS`; `entities.ts` alone is what `detailModel.ts`
needs for its dual read. `SaveDetailScreen`'s `recommendationItemsField` now
computes each item's `entityKey` and threads it onto `DetailObject`;
`resolveObjectState` reads `entityState ?? itemState` (entity wins) and the
watch/rating control's `onChange` now PATCHes `/v1/entity-state` instead of
`/v1/saves/{id}/item-state` — the exact "watched/rating controls move from
save-item-keyed to entity-keyed writes" the phase was scoped to.
Checklist/course/workout completion is untouched, still item-path-only, per
the doc's stored-state section above. `mockData.ts` gained two overlapping
`recommendation_list` saves (`sv-11`/`sv-12`, sharing "Blue Box") so the merge
path — not just a happy singleton — is exercised in mock mode too.

**Verified two ways, both real, neither mocked-only:**

- **Standalone under node** (this repo's standing technique): the TS `merge.ts`
  port, run directly against the same normalize spec `EntitiesTest.java`
  pins (all 7 real K0 titles → distinct keys) and against the `sv-11`/`sv-12`
  fixture — 3 distinct entities from 4 occurrences, Blue Box's year resolving
  to the one non-`[unclear]` source, genre unioned, both reasons kept
  unblended per source, `entityCount` equal to the merged list's length (the
  K1 exit criterion, now checked in two languages), `withDoneCount` counting
  exactly the one entity marked done.
- **Live against Supabase**, the same throwaway-user methodology K1 used: a
  fresh admin-API user (never `/auth/v1/signup`), two saves seeded directly
  into `saves` sharing a "Blue Box" item. `GET /v1/collections` returned
  `entityCount: 2, doneCount: 0, sourceCount: 2` before any state was set;
  `PATCH /v1/entity-state {entityKey: "screen:blue box", state: {done: true,
  rating: 5}}` returned `200` with the written state; a second `GET
  /v1/collections` showed `doneCount: 1`, and `GET
  /v1/collections/recommendation_list` showed the same entity carrying
  `state: {done: true, rating: 5}` — the whole write-then-join round trip,
  against the real database, not a mock. Flyway's own log confirmed `V13`
  applied cleanly (`Migrating schema "public" to version "13 - entity
  states"` → `Successfully applied 1 migration`). Throwaway user, both saves,
  and the one `entity_states` row were all deleted afterward; a follow-up
  query confirmed zero rows left under that user id in `saves`,
  `entity_states`, and `profiles`.

Backend suite green (373/373 — 8 new: `EntityStateServiceTest` mirrors
`SaveItemStateServiceTest`'s mocked-`JdbcClient` style since there is still no
local Postgres to run the real upsert against in-suite; `CollectionServiceTest`
gained three pure tests pinning `state` staying `null` from the merge core,
`doneCount` staying 0 from the pure builder, and `withDoneCount`'s
distinct-entity counting).

### K3 — the Library becomes collections-first — done, 2026-08-07

The user-visible half, scoped to exactly what K1/K2 serve:

- **Library's top level for entity-bearing types renders collections, not
  save rows.** `LibraryScreen` fetches `GET /v1/collections` alongside its
  existing `useSaves()` feed and renders one `CollectionCard` per top-level
  node ("Recommendations — 3 titles · 1 watched · 2 sources") in a new
  Collections section. Any `knowledgeType` that produced a node is then
  excluded from the "By type" tiles, the filter chips, and the flat
  "Everything" list — those saves are represented by the collection card now,
  not as individual rows — **except** a type that produced *no* node (every
  item's name was `[unclear]`) still falls through to the ordinary flat
  presentation, so a save is never simply dropped from the Library. Other
  types (workout/course/etc., shapes 2 and 3) are entirely unaffected, per
  shape 3's own instruction not to force the metaphor onto them.
- **A collection screen** (`/collection/[type]`, `CollectionDetailScreen`,
  `slide_from_right` like `group/[id]`): entities sectioned into "Remaining"
  and a done-noun section ("Watched" for recommendation_list, "Done" for
  checklist), derived from `state.done` at render time, never stored as a
  section. The doc's original three-way Planning/Watching/Completed split
  for `screen` entities was scoped down to a two-way Remaining/Done split
  during implementation: nothing anywhere writes a `state.status` field
  (`WatchControl` only ever writes `done`/`rating`), so a three-way section
  would have had no way to populate its middle bucket — building UI for a
  field nothing produces is exactly the kind of invented affordance this
  repo's conventions warn against. Revisit if a future phase adds a `status`
  write.
- **An entity detail sheet**: every source's own `reason` attributed to its
  own source (never blended — confirmed rendering as two separate cards in
  the live headless-Chrome run below), each with an "Open source" link to
  `/save/[id]` — the save detail screen is unchanged, and is now explicitly
  the provenance view. Implemented as a plain React Native `Modal` driven by
  local component state rather than the shared `Sheet` chrome: `Sheet`'s
  `dismiss` calls `router.back()`, which assumes the sheet is its own pushed
  route — this one opens from an entity already held in the collection
  screen's own fetched list, so a route (and the entity-key path-encoding
  it would need, since keys contain `:` and spaces) buys nothing.
  `PATCH /v1/entity-state` from this sheet needs no dual-read fallback the
  way the save detail screen's does — the collection screen is a brand-new
  surface with no legacy `save_item_states` to migrate away from, so it
  reads and writes entity state directly.
- **Mark-done/rate is generic across all three wired types** — `toggleDone`/
  `rate` in `CollectionDetailScreen` call `repo.setEntityState` regardless of
  `type`, since `PATCH /v1/entity-state` has no type-specific validation
  server-side. The 1–5 star row is gated to `recommendation_list` only
  (`collectionTypeMeta(type).ratable`), so a checklist task doesn't get a
  meaningless rating control.

**Verified live, driven not just screenshotted**, per `docs/testing.md`'s CDP
recipe, against `USE_MOCK_DATA = true` (flipped locally, never committed —
the pre-commit hook forces it back regardless) and the `sv-11`/`sv-12` mock
fixtures: Home → tap Library → the Collections section shows "Recommendations
— 3 titles · 2 sources" with the two saves already gone from "By type" and
"Everything" → tap through to the collection screen, all three entities under
"Remaining", Blue Box correctly showing "2 sources" → tap Blue Box, the sheet
shows both reasons in separate cards, each with its own "Open source" →
tap "Mark watched" → the collection screen's own header updates live to "1
watched", Blue Box moves to a new "Watched" section with a check icon → tap
"Open source" on one of Blue Box's sources → lands on that save's own detail
screen, where the *same* Blue Box item (a different `items[]` entry, from a
different save, never itself touched) already shows "Watched" — the K2
dual-read working end-to-end, not just unit-tested. Six screenshots, not one.

Verification bar, all met: model logic executed standalone under node
(above), headless Chrome for the screens (above), mock fixtures extended with
overlapping entities so the merge path was exercised rather than the happy
singleton (above), and — beyond what the phase originally asked for — a real
live-Supabase round trip for K2's write path, matching K1's own bar rather
than settling for K2 being "mocked but not proven."

### K4 — identity upgrades (each independent) — done, 2026-08-07

All three, landed together:

- **Canonical ids from enrichment.** `TmdbEnricher` and
  `RecommendationListEnricher` now write `tmdbId` alongside (or, for a
  poster-less match, instead of) `posterUrl` on a confident match — the
  latter changed shape slightly to make this possible: a match with no
  poster used to return `Map.of()` and be silently dropped, which would have
  thrown away the one thing K4 needed from it. `Entities.key` gained a
  three-arg overload (`key(kind, name, canonicalId)`) that returns
  `"tmdb:" + canonicalId` whenever a canonical id is present, overriding the
  string key entirely — `CollectionService` reads `item.get("tmdbId")` at
  the exact point it already computes an entity key, for both shape-1 items
  and shape-2 saves. This is confirmed to resolve the alias case the doc
  names: "Shingeki no Kyojin" and "Attack on Titan" sharing a `tmdbId`
  collide into one entity even though no normalization rule over the
  strings would ever unify them (`EntitiesTest
  .aCanonicalIdOverridesTheStringKeyEntirely`,
  `CollectionServiceTest.itemsSharingATmdbIdMergeEvenWithDifferentSurfaceForms`).
  **Still the stated known gap**: no `WEAVR_TMDB_API_KEY` exists in this
  environment, so `tmdbId` being written correctly is mock-verified only,
  the same standing gap `TmdbEnricherTest` already documented — nothing
  about K4 closes it.
- **Shape 2 joins the key space, scoped exactly as the doc's own phrase
  puts it** — "the review attaching to the watchlist entry," not shape 2
  gaining a top-level collection of its own. `CollectionService.index` now
  also folds in `shape2Occurrences(ready)`: every ready `movie`/`book`/
  `place`/`product`/`recipe`/`github_repo` save, reduced to a single
  synthetic occurrence of itself (its own `structuredData` minus its name
  field), keyed the same way a shape-1 item is. A shape-2 occurrence is
  merged in **only** when a shape-1 item already produced that key —
  `byEntity.get(key)` must already exist — so a `movie` save with no
  matching watchlist entry contributes nothing and stays an ordinary,
  individually-presented save exactly as before K4, and `GET
  /v1/collections/movie` still returns `[]`, unchanged from K1
  (`CollectionServiceTest.shape2TypesStillProduceNoTopLevelNodeOfTheirOwn`).
  Because field rollup was already generic over field names (K1), a movie
  review's `director`/`synopsis` roll up onto the merged entity for free,
  right alongside the watchlist item's own `reason` — no field-specific
  code needed, proven by `shape2MovieSaveJoinsAnExistingWatchlistEntityAsAnAdditionalSource`.
- **`V14__collection_overrides.sql`**, trimmed from the doc's own four-item
  sketch to three `override_type` values — `entity_merge`, `entity_rename`,
  `collection_rename` — with the reasoning for the cut stated in the
  migration itself: merge-undo is a `DELETE` of an `entity_merge` row, not a
  fourth type, and **pin rides `entity_states`' existing `state` jsonb
  (`state.pinned`)** instead of a fourth type, reusing K2's already-proven
  per-(user, entity) mechanism rather than adding a parallel one for a
  single boolean. `CollectionOverrideService` (`JdbcClient`, the
  `EntityStateService` shape) loads every override row for a user in one
  query and resolves merge chains to their final target with a
  cycle-guarded walk (`resolveChains`, max 32 hops) — covers a user
  re-merging into something that eventually points back at the start
  without hanging. `CollectionOverrides` is threaded through the pure merge
  core as **data, not a service reference** (`buildTree`/`mergeType` gained
  a fourth parameter, defaulting to `EMPTY_OVERRIDES` for every existing
  caller), so the core stays exactly as database-free and unit-testable as
  K1 left it — a manual merge is a redirect applied at the same point an
  entity key is first computed, an entity/collection rename is a lookup
  applied where the name is resolved, never a second pass over already-built
  data. New endpoints: `POST /v1/collection-overrides/merge`,
  `POST .../unmerge`, `PATCH .../entity-name`, `PATCH .../collection-name`
  — all body-over-path, the `EntityStateController` precedent, since entity
  keys and collection ids both carry characters (`:`, spaces, `~`) that
  would need encoding as a path segment.

**App-side, `@/collections/merge.ts` and `@/collections/entities.ts` grew
the identical three capabilities**, by hand, the same "two languages, one
port" discipline K2 established — `entityKey`'s three-arg overload, the
`SHAPE2_TYPES` join folded into `indexType`, and a `CollectionOverrides`
type threaded through `mergeType`/`buildTree` the same optional-fourth-
parameter way. `mockRepository` gained three module-scope override stores
(`mergeRedirects`, `entityNameOverrides`, `collectionNameOverrides`) and the
same `resolveChains`-at-read-time split the Java service makes, so mock mode
exercises the real merge path rather than a hand-authored one. **Pin is the
one piece with real UI**: `CollectionDetailScreen`'s entity sheet gained a
bookmark-icon toggle (new `Glyph` — Feather-style, no icon library, matching
every other icon in the app) writing `state.pinned` through the existing
`setEntityState` full-replace path, and pinned entities sort first within
their Remaining/Done section. **Merge and rename have no UI** — the data
layer, endpoints and repo methods (`mergeEntities`, `unmergeEntity`,
`renameEntity`, `renameCollection`) are real and tested, but nothing in this
pass builds a picker to trigger them. This mirrors the doc's own "build only
what K0/K3 usage shows is needed" instruction taken literally: there is
still no duplicate-detection signal anywhere in the app (measured-first
rule, unchanged since K1) to tell a user two entities are worth merging by
hand, so a trigger UI would be inventing an affordance nothing points at
yet.

Verified: full backend suite green (**393/393, 6 opt-in skipped** — 20 new:
`EntitiesTest` (3 cases for the canonical-id overload),
`CollectionServiceTest` (8: canonical id merge, shape-2 join happy path +
no-match + no-top-level-node, manual merge across both shape 1 and a
shape-2 occurrence, entity rename, collection rename),
`CollectionOverrideServiceTest` (8, mirroring `EntityStateServiceTest`'s
mocked-`JdbcClient` style — including the chain-resolution and cycle-guard
logic pinned directly, not just through `CollectionService`), plus one new
`tmdbId`-without-a-poster case in `RecommendationListEnricherTest` and
`tmdbId` assertions folded into two already-existing `TmdbEnricherTest`
cases). App typechecks,
`expo export --platform web` bundles clean, and the updated `merge.ts`/
`entities.ts`/`workoutCompare.ts` (K5, below) were executed standalone under
node (`node --experimental-strip-types`, this repo's now-current variant of
the standing "run the model under node" technique) against 26 hand-built
assertions covering canonical-id collision, the shape-2 join's happy path
and its "no match, no entity" path, manual-merge and rename overrides, and
`EMPTY_OVERRIDES` being a true no-op — all 26 passed against the real
shipped TypeScript, not a paraphrase of it. **Not run against a live
Supabase** — unlike K1/K2's throwaway-user round trips, this pass had no
opportunity to seed a `tmdbId`-bearing save or drive the new endpoints
against a real database; that gap is explicit, not assumed away, and stated
here rather than left implicit like the TMDB key gap it inherits. **Not run
on a device**, same standing caveat as the rest of the app's UI work.

### K5 — synthesis (budgeted or never) — done, 2026-08-07: the constraint held, so the alternative shipped instead

The vision's "AI continuously improves the program" / collection summaries
was evaluated against exactly the bar this section set in advance — generated
only on view, cached, and **a new budget line in CLAUDE.md's request-budget
section before the first call is written** — and the honest answer is that no
such line was written. Nothing forced the decision technically; it follows
the same reasoning `recipe.estimatedNutrition` was cut on in Phase 5 §5.1
("a wrong estimate has real-world consequences a difficulty guess doesn't")
and the same one the digest's own "budget explicitly, don't let a feature eat
shared capacity" rule states — a per-view generative rewrite of someone's
workout program is exactly the kind of standing draw against the 500-RPD pool
this doc's own constraint 2 exists to prevent, and unlike a digest it has no
natural weekly cache key to bound how often it fires. **So this phase, as
specified, does not exist** — per its own stated rule, stated once more here
rather than silently: if the budget line can't be justified, it doesn't ship.

**What shipped instead is the doc's own named alternative**, in full:
side-by-side compare for workouts, entirely local compute, zero AI, the same
cost class Phase 5 §5.2's `workoutLoadField` already established for a
single workout's own session-length estimate — extended across several
rather than reasoning across them.

- **`app/src/saves/workoutCompare.ts`** (`compareWorkouts`) is pure: given a
  list of saves, it drops anything that isn't a usable `workout` save and
  reduces each survivor to a row read straight out of `structuredData` — no
  merge, no rewrite, no field is ever blended across sources the way a
  `recommendation_list` entity's fields are. A stated `duration`/`difficulty`
  wins over the §5.1 estimate fields exactly like `detailModel.ts` already
  prefers the creator's own claim, and a row carries its own
  `durationIsEstimate`/`difficultyIsEstimate` flag rather than silently
  reusing an estimate as if it were stated — the same "distinguishable from
  extracted ones" contract §5.1 already committed to, carried into a second
  screen instead of invented fresh for it. The only thing computed *across*
  rows is a plain set intersection — `commonMuscleGroups`/`commonEquipment`,
  suppressed below two rows — which is aggregation (constraint 4's free
  half), not synthesis.
- **`WorkoutCompareScreen`** (`/compare-workouts`, routed
  `slide_from_right`) renders one column per selected save in a horizontal
  scroll plus an "In common" card — nothing merged, every source's own
  numbers stay attributed to its own column, mirroring the same "reasons
  are never blended" rule the recommendation-list entity sheet already
  follows for a different kind of source.
- **The entry point is scoped to exactly the workout group**, not a general
  group feature: `GroupDetailScreen` gained a "Compare" toggle that only
  renders when `id` is the `workout` top-level group or one of its facet
  subgroups, `hasSubgroups` is false (comparison operates on leaf saves, the
  same reason multi-select stays out of a screen still showing subgroup
  rows) and at least two `ready` workout saves exist. Selecting reuses
  `SaveCard`'s existing `selectionMode`/`selected`/`onLongPress` props
  (already built for Library's favorite/archive bulk actions in an earlier
  phase) rather than a new selection component — long-press enters compare
  mode on the pressed card the same way it enters multi-select in the
  Library, and a bottom action bar reading "Compare N workouts" (disabled
  under two selections) navigates to `/compare-workouts` with the selected
  ids joined into one `ids` query param, plain comma-separated rather than
  Expo Router's array-param handling, since a handful of workout ids never
  needs more than that.
- **No backend change at all.** Every field `compareWorkouts` reads is
  already served by the ordinary `GET /v1/saves`/`GET /v1/groups` calls the
  screen already makes — the same "interactivity is client + Postgres, not
  AI" rule Phase 5's own design principle states, applied to a comparison
  instead of a completion tick.

Verified: `workoutCompare.ts` executed standalone under node against 9
assertions (drops a non-workout save from the comparison, prefers a stated
duration/difficulty over the estimate and flags the estimate correctly when
it's the only value present, computes the muscle-group and equipment
intersections correctly, and confirms "common" stays empty under two rows)
— all passed against the real shipped module, same run as K4's verification
above. App typechecks and `expo export --platform web` bundles clean,
including the new route and the `GroupDetailScreen` multi-select changes.
**Not driven through headless Chrome or on a device** — this phase touched
only the workout group screen's header/selection state and a new screen
built from already-proven primitives (`SaveCard`, `Card`, `Screen`,
`Reveal`), so it inherits rather than re-earns Library's own multi-select
verification the way Phase 5's related-saves rail inherited the Home rail's;
the swipe-gesture lesson from that Library work doesn't apply here since
compare-mode selection is plain taps, the one interaction CDP synthetic
events do reliably trigger, but that run itself was not repeated for this
screen specifically.

---

### K6 — the collection becomes a hierarchy (landed 2026-08-10)

K1–K5 merged entities correctly but presented them as **one flat list per
type**, and the tree they built was never rendered. The result read as
"I found 7 places in one save", which is an extraction, not a collection.
K6 is the structural half of the fix — the aggregation and hierarchy model,
deliberately *without* the domain-specific state and action surfaces, which
are the next pass.

**The axis chain replaces the single facet.** `CollectionAxes.java` (mirrored
in `app/src/collections/axes.ts`) generalises K1's one save-level facet per
type in three directions at once, and `CollectionService.buildLevel` is the
single recursive function that consumes them:

| | K1–K5 | K6 |
|---|---|---|
| Depth | one level | a chain per type — Recommendations → Anime → Romance |
| Source | the save's own field | the save's field **or** the merged entity's |
| Cardinality | one value per save | many — `genre`, `muscleGroups` |
| Threshold | one global `MIN_GROUP_SIZE = 5` | per-axis, because the right floor differs |

The wired chains: `recommendation_list` splits by item `kind` then `genre`
(both entity-level, so a title recommended by an anime list *and* a film list
is filed once by what it **is**, not twice by what each list was about);
`itinerary` by save-level `destination`; `checklist` by `category`; and
`workout` — new here — by a **derived** training split.

**`workout` joins shape 1.** Its `exercises[]` is an item-bearing list like
any other, and merging it yields "Bench press, in 2 of your push days". What
stays out of scope is synthesising a *new* routine from several saves — the
thing this doc warns against forcing onto shape 3. A merged exercise is a
fact about the library; an invented program is not.

Five decisions worth carrying forward, three of which were forced by running
the thing rather than reasoning about it:

- **Node resolution must not depend on the display threshold.** The first
  implementation answered `GET /v1/collections/{nodeId}` by building the tree
  and finding the node in it — so a subgroup too small to be worth *showing*
  answered with **nothing at all**, and a perfectly well-formed node id
  resolved empty. `mergeNode` now walks the axis path, filtering the merged
  set by each segment's value in turn. Showing a folder and resolving a folder
  are separate questions; tying them together is a silent wrong answer. It is
  also cheaper — a filter, not a tree build.
- **A derived axis has to see the whole set, not each value.** `muscleGroups`
  → Push/Pull/Legs mapped per value first, and the fixtures immediately showed
  why that is wrong: a full-body circuit trains chest, so its **squat appeared
  under Push**. The split is a property of the *session* — there is no
  per-exercise muscle group in the schema — so a session spanning three or
  more splits is one `Full body` session rather than being scattered across
  all of them. `Core` is excluded from that span, since nearly every session
  trains it and it would otherwise make "Full body" the only bucket anyone
  sees. The axis's `derive` is therefore `List<String> → List<String>`, not
  `String → List<String>`.
- **A key falls through to loose only when *no* bucket it landed in
  survived.** Deciding per bucket lists a title inside Romance *and* loose
  beside it, because its second genre happened to be rare. The distinct-count
  rule from K1 is now load-bearing at every depth, not only the first: three
  anime across Romance (2) and Sports (2) is **3 titles**, not 4.
- **A node id keeps the extracted vocabulary; only its name is
  product-facing.** `recommendation_list~film` is named "Movies". Renaming a
  folder can never invalidate a deep link, and `KIND_DISPLAY_NAMES` stays a
  closed-ish map falling back to title case, exactly like `DISPLAY_NAMES`.
- **An empty type node is worse than none.** A workout save with no
  `exercises` used to produce a collection with nothing in it — and since the
  Library steps a type's tiles aside once it has a collection, an empty node
  would hide those saves behind nothing. `buildTree` now drops any type node
  whose `entityCount` is 0.

**App side, one screen serves every level.** `CollectionDetailScreen` takes a
node id, lists whatever folders that node has, then whatever entities sit
*directly* on it, and pushes itself for a child — so the navigation stack is
the breadcrumb trail, back goes up exactly one level, and arbitrary depth
costs no extra screen. Same property `GroupDetailScreen` gets from the group
tree. The route is still `/collection/[type]`; the param now carries a node
id, which is one path segment by construction since `slug` strips `~`.

Two presentation rules, both aimed at not making the user care about the
extraction structure:

- **The section heading is named for what the objects are** — PLACES,
  WATCHLIST, EXERCISES, TASKS — not "Remaining". Only a checklist is a list of
  things left to do; heading an itinerary's places that way made every
  collection read as the same generic to-do screen.
- **`kind` renders only when it varies.** `workout` and `checklist` have a
  fixed kind every item shares, so showing it put the word "exercise" under
  every row of a screen headed EXERCISES. Caught by reading a CDP dump, not by
  a test.

**"Everything" is now genuinely everything.** Saves whose type produced a
collection are no longer excluded from the flat list — a reversal of K3.
Collections are derived organisation; Everything is the raw source history,
and it has to be complete to be the safety net it exists to be. Duplication
between the two views is the point.

**Verified.** Backend suite green (**431/431**, 6 opt-in skipped — 15 new
`CollectionServiceTest` cases covering the four structural paths end to end,
the multi-valued distinct count, the loose-fallthrough rule, threshold
independence, and the full-body/Core/unmapped-muscle derivation). App
typechecks; `expo export --platform web` bundles clean. **69 node-standalone
assertions** over the real shipped `merge.ts`/`axes.ts`/`collectionMeta.ts`
against the real shipped `mockData.ts` fixtures. **59 CDP checks** driving the
real app on `expo start --web` with `USE_MOCK_DATA` flipped locally (never
committed): Library → Itineraries → Japan → Tokyo's entity sheet naming both
source saves, Recommendations → Anime → Romance, Workouts → Push → Bench
press, plus a done-toggle write that survived a page reload. Three of the
five CDP failures in the first run were the probe matching **Home's** group
card rather than the Library's collection card — the mounted-pane trap this
repo's testing doc already warns about, hit again. **Not run on a device.**

**Known gaps, stated rather than hidden.** A workout entity's detail sheet
reads "No details yet" (its rolled-up `sets`/`reps` are present in `fields`
but nothing renders them — domain presentation, next pass). Two Japan trips
years apart still merge into one Japan, which is open question 2, still
unanswered on real data. And `checklist` remains wired to shape 1 without the
real-data check open question 1 asked for — no checklist save exists yet.

---

## Explicitly out, and why

- **Reprocessing old saves into new shapes** — no reprocess path exists; the
  derived layer is precisely how old saves join collections without one.
- **A merge/dedupe model call at save time** — constraint 2. The classify
  call's existing output is the merge input; nothing new is asked of it.
  (If K0 shows normalization needs help, the *legal* lever is a same-call
  token: a `canonicalName` field in the item schema, prompt-steered toward
  the franchise's best-known English name. Try that before any new call.)
- **Storing the collection tree or merged entities** — constraint 3. Only
  state and overrides are stored.
- **Auto-merge via embedding similarity** — measured-first rule; a silent
  wrong merge is the k-NN "no such thing as no-match" failure wearing new
  clothes.
- **Collection-level AI naming at K1–K3** — "Anime Watchlist" falls out of
  `DISPLAY_NAMES` + facet ("Recommendations · Anime"); a nicer AI-written
  name is a K5 token spend, and a rename override (K4) covers taste for free.

## Open questions (answer during K0/K1, not by fiat)

1. Does `checklist` merge at all? Two packing lists for different trips
   sharing "passport" should probably *not* merge — the facet (`category`)
   may be too coarse a collection boundary for task-like types. K0 should
   look at real checklist saves before wiring shape 1 to them.
2. Is `itinerary`'s collection boundary the facet (`destination`) alone, or
   destination + rough time? (Two Japan trips years apart.) Default: facet
   alone, revisit on real data.
3. Where does entity state live for Space-shared collections — is a shared
   watchlist's "watched by 2 of 3 members" worth surfacing? (Per-user state
   supports it read-side with no schema change; UI question only.)
   *Now answered in depth by [knowledge-spaces.md](knowledge-spaces.md),
   which builds the Space-scoped counterpart of this whole layer on K1/K2.*
