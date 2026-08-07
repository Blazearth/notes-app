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

### K0 — measure before building (a spike, not a feature)

Against the dev account's real saves, via curl (per the repo's
verify-via-curl rule): pull every `recommendation_list` save, run the
normalization spec over their items *offline*, and count (a) true duplicate
entities caught, (b) false merges (different things, same key), (c) misses
(same thing, different key — the alias rate). This is the escalation-threshold
lesson applied here: without a labelled baseline, the normalization rules are
guesses, and both failure directions are silent. If the dev library is too
thin, save ~10 real recommendation Reels first — that's what they cost 10 of
500 RPD for. **Exit criterion: the normalize spec frozen against measured
data, plus a decision on whether leading-article stripping and kind
coarsening earn their place.**

### K1 — the derived merge, server-side

`collection/` module: `Entities.key` (pure static), `CollectionService`
(derive collections + merged entities from ready saves, shapes 1 and 2's
*read* path but only shape 1 wired), the two GET endpoints. No migration, no
app change yet.

- Tests mirror `GroupServiceTest`: hand-built saves, single- and multi-valued
  facets, and — pinned as a named regression, since it is the constraint-3
  trap — one entity in three sources counts once, and a collection's
  `entityCount` equals the length of its merged list.
- Merge precedence rules (first non-`[unclear]`, genre union, reason
  attribution) each get a test; the whole merge is a pure function over a
  list of saves, `fold`-style.
- **Verify live**: curl the endpoint against the dev account's real
  Supabase-backed saves. The `GroupService` history says the first real
  library will overturn at least one assumption the unit fixtures baked in
  (it was multi-valued genres last time).

### K2 — entity state

`V13__entity_states.sql`, `EntityStateService` (JdbcClient, `ShoppingListService`
style), the PATCH endpoint, state joined into K1's entity payload. App-side:
the optimistic-update path reuses the `setItemState` pattern (flip local,
PATCH, adopt echo, reload on failure); dual-read wiring for
`recommendation_list` items; the watched/rating controls move from
save-item-keyed to entity-keyed writes.

### K3 — the Library becomes collections-first

The user-visible half, scoped to what K1/K2 serve:

- Library's top level for entity-bearing types renders **collections**
  ("Anime Watchlist — 27 anime · 12 watched · 8 remaining"), not save rows.
  Other types keep today's presentation — do not force the metaphor onto
  workout/course, per shape 3.
- A collection screen: entities sectioned by state (Planning / Watching /
  Completed for `screen` entities — derived from `state.status`/`done`, never
  stored as sections), each entity showing poster, merged meta, source count.
- An entity detail sheet: every source's reason *attributed to its source*,
  and a Sources rail of the original saves — tap-through to the save detail,
  which is where "open the original Reel" already works. The save detail
  screen is unchanged; it is now the provenance view.
- Verification bar, per repo convention: model logic executed standalone
  under node (old-shape fixtures included — a pre-K1 `recommendation_list`
  save must render), headless Chrome with `USE_MOCK_DATA` for the screens,
  mock fixtures extended with overlapping entities so the merge path is
  exercised, not just the happy singleton.

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
