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

### K4 — identity upgrades (each independent)

- **Canonical ids from enrichment**: `RecommendationListEnricher` and
  `TmdbEnricher` store `tmdbId` alongside `posterUrl` on a confident match
  (additive per-item merge machinery already exists — `mergeItemLists`).
  `Entities.key` prefers `tmdb:<id>` over the string key, which resolves the
  alias problem for exactly the entities TMDB knows. Needs a real
  `WEAVR_TMDB_API_KEY` live-verified first — the whole TMDB path is
  mock-only today (known gap, `TmdbEnricherTest`).
- **Shape 2 (save-is-the-entity) joins the key space**: a `movie` save's
  title resolves to the same entity as a watchlist item. Renders as the
  review attaching to the watchlist entry.
- **`V14__collection_overrides.sql`**: manual merge/split + rename, applied
  over the derived view. Build only what K0/K3 usage shows is needed.

### K5 — synthesis (budgeted or never)

The vision's "AI continuously improves the program" / collection summaries.
Not designed here beyond its constraints, which are the digest's: generated
only on view, cached per (user, collection, content-hash of source ids),
plain-text out, and **a new budget line in CLAUDE.md's request-budget section
before the first call is written** — against the 500 pool, never the 20-RPD
Flash pool. If the budget line can't be justified, this phase doesn't exist,
and the compare-side-by-side local-compute alternative for workouts is what
ships instead.

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
