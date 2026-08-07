# Implementing Phases 3–5: new types, object behaviors, derived intelligence

This is the implementation plan for the remaining phases of the object-generation
roadmap ([CLAUDE.md → "Design principle: optimize for future usability"](../CLAUDE.md)).
Phases 1–2 landed 2026-08-07 (`24dcc78`): the registry supports `objectArray`
fields end to end, `workout` extracts the full routine, `recipe` extracts
structured ingredients, and the app renders nested objects generically — an
unknown type's object arrays render as sub-cards instead of vanishing.

Everything below is written against that state. Nothing here requires new
schema-builder work; the enabler phase is done.

---

## Ground rules (apply to every phase)

These are restated from CLAUDE.md because every one of them was earned, not
guessed:

1. **One Gemini generation call per save, ever.** New types are new `anyOf`
   branches in the same call. "Post-processing" that needs a second generation
   call is out of design space — derived value comes from same-call tokens,
   local compute, or the enrichment/embedding stages.
2. **Old saves keep their shapes forever.** There is no reprocess path (the
   embed job dedupes on save id). Any consumer of a field whose shape changes
   must handle both shapes permanently — see `ConvertToShoppingListHandler.ingredientLines`
   and `ingredientText` in `detailModel.ts` for the pattern.
3. **No invention.** `[unclear]` for anything the content doesn't state, at
   every nesting level. Derived/estimated values (Phase 5) must be
   *distinguishable* from extracted ones — never stored under a field name that
   reads as fact.
4. **The verification bar per change**, in this repo's terms:
   - New/changed registry types → **live schema pre-flight**: serialize the real
     `buildResponseSchema()` + `buildSystemPrompt()` into one `generateContent`
     request against the real key (costs 1 of 500 RPD). The 2026-08-07
     pre-flight caught nothing because the schema was fine — the point is the
     day it isn't, it fails a curl instead of production classify jobs.
   - New migration touching generated columns / jsonpath → **probe the live DB
     first** via a read-only JDBC one-off through the session pooler (no local
     Postgres exists; the V11 jsonpath was verified this way).
   - App model logic → compile `detailModel.ts`/`cardModel.ts` standalone
     (they import only types) and **execute under node** with old-shape,
     new-shape, and unknown-type fixtures.
   - New UI → **headless Chrome** with `USE_MOCK_DATA=true` and
     `--force-prefers-reduced-motion`, before any device claim.
5. **Budget the Flash pool at 20 RPD, not 250.** Nothing in Phases 3–5 may add
   a Flash consumer without re-checking the rate-limit table.

---

## Phase 3 — new intent-named types

**Landed 2026-08-07.** All five types below shipped in one batch: `recommendation_list`,
`checklist`, `itinerary`, `course`, `github_repo`. Per type, registry entry + few-shot,
`GroupService` facet, and `saveTypeMeta.ts` icon/colour/label all landed; bespoke
card/detail layouts (optional per the table below) landed only for `recommendation_list`,
the flagship — the other four render through the generic fallback, which Phase 1–2 had
already proven handles an unknown type's nested object arrays. The live schema pre-flight
(`KnowledgeTypeRegistrySchemaLiveTest`, `WEAVR_LIVE_GEMINI=1`) posted the real 14-branch
`anyOf` against the real API and got a 0.98-confidence `recommendation_list` extraction
back, `reason` populated on every item — see CLAUDE.md's Phase 3 paragraph for the full
verification record, including the `Map.of` → `Map.ofEntries` fix `GroupService` needed
once facet/display-name maps passed 10 entries.

All server-side additions are registry entries (data change). Per type, the
full checklist is:

| Step | Where | Required? |
|---|---|---|
| Registry entry: fields + few-shot | `KnowledgeTypeRegistry` | yes |
| Schema pre-flight against real API | scratch probe (see ground rule 4) | yes, once per batch of types |
| Type icon + colour + label | `app/src/saves/saveTypeMeta.ts` | yes (falls back ugly, not broken) |
| Groups facet for the type | `api/.../group/` facet map | yes — pick a closed-ish vocabulary field |
| Bespoke card layout | `cardModel.ts` | optional — flat `ListRow` fallback works |
| Bespoke detail layout | `detailModel.ts` | optional — generic sub-cards work (proven) |
| Detail claim set | `HANDLED_ELSEWHERE` in `detailModel.ts` | only with a bespoke branch |

**Do them in this order.** Each type is shippable alone; a half-finished batch
of five types is worth less than two finished ones.

### 3.1 `recommendation_list` — the watchlist (first, it's the flagship case)

The type that exists because a "top 10 romance anime" video is a list to work
through, not a movie and not a summary.

```
title            string   — the list's own subject, e.g. "Underrated Romance Anime"
medium           string   — one of: anime, film, tv, book, game, music, podcast, place, product, mixed
summary          string   — one sentence on the list's angle
items            objectArray:
  name           string   — the recommended thing itself
  kind           string   — e.g. 'film', 'series' (lists mix kinds; per-item, not per-list)
  year           string   — or [unclear]
  genre          array    — or []
  reason         string   — WHY the creator recommends it; the most valuable field, or [unclear]
  platform       string   — where to watch/read if stated, or [unclear]
  rank           string   — position if the list is ordered, or [unclear]
orderMatters     string   — 'yes' if the creator prescribes a viewing order, else 'no'
```

- **`reason` is the field to fight for in the few-shot.** Every list video
  says *why* ("best enemies-to-lovers arc of the decade"); a watchlist entry
  without the reason is just a title the user won't remember saving.
- **The few-shot must be a discriminator against `movie`.** Use an input that
  names several titles with commentary — the exact content that today
  misclassifies as `movie` (one title kept, nine lost) or `other` (all ten
  summarized away). The existing `movie` few-shot (single film, single review)
  stays as the contrast.
- Watch status / progress / personal rating are **Phase 4 user state**, not
  extraction fields. Do not add them to the schema — the model has nothing to
  extract them from, and `[unclear]` noise on every item is pure cost.
- Groups facet: `medium`, then per-item `genre` is a candidate for a deeper
  facet later.

### 3.2 `checklist`

Steps/tasks content: tutorials phrased as "do these 7 things", packing lists,
setup guides. Fields: `title`, `summary`, `context` (what it prepares you
for), `items` objectArray of `{text, detail, optional}`. The simplest new
type — land it right after `recommendation_list` to prove the checklist→Phase-4
tick-off pipeline on something small.

### 3.3 `itinerary` — travel guide

`title`, `destination`, `durationDays`, `summary`, `bestSeason`,
`places` objectArray of `{name, kind (sight/restaurant/hotel/area), area,
cost, timeNeeded, tips[], day (if the content assigns one, else [unclear])}`,
`generalTips[]`. Enrichment already knows Places; **additive-only with the
similarity threshold** — a wrong Places match on "that noodle shop near the
station" must not overwrite the creator's description.

### 3.4 `course` / tutorial

Learning content with structure: `title`, `subject`, `level (only if
stated)`, `summary`, `sections` objectArray of `{name, covers, duration}`,
`prerequisites[]`, `resources[]` (URLs/books/tools named in the content),
`outcomes[]`. Reading progress is Phase 4 state.

### 3.5 `github_repo`

`name`, `owner`, `summary`, `language`, `purpose`, `setup[]` (install/run
steps as stated), `commands` objectArray of `{command, does}`, `technologies[]`.
Consider a free enrichment: the public GitHub API (stars, license, default
branch) is keyless for public repos — but that is a new enrichment client and
can trail the type itself.

### Classification steering, batch-wide

- The intent rule already leads the prompt rules. When the new types land,
  add one line to the type *descriptions* that names the confusion pair:
  `recommendation_list`'s description should say "use this, not `movie`/`book`,
  when several things are recommended".
- After the batch lands, spot-check with real content: one recommendations
  video, one tutorial, one travel vlog through the live pipeline (3 requests).
  The 2026-08-07 pre-flight pattern proves the schema; only real mixed content
  proves the *routing*.
- **Watch prompt growth.** Nine types ≈ moderate; fourteen few-shots is still
  cheap in tokens, but keep new examples to 2 items per objectArray — the
  workout example's 2 exercises were enough to teach the shape.

---

## Phase 4 — object behaviors (client + Postgres, zero AI)

**Landed 2026-08-07** — see [CLAUDE.md](../CLAUDE.md) for the full verification
record (backend 329/329, standalone `buildDetailModel`/`buildCardModel`
execution, headless-Chrome click-through with `USE_MOCK_DATA`). Everything
below shipped as specified: the one mechanism, all six per-type behaviors,
in the order §4.3 lays out. Nothing here needed a design change from the plan
— the sketch's table/endpoint shape, the "identity is the array index"
precondition, and the replace-don't-accumulate write rule all held exactly as
written.

The extracted object is read-only pipeline output. Behaviors are user-mutable
state layered on top — the shopping list's ticked-item pattern, generalized.

### 4.1 The one mechanism: per-item save state

One table, one endpoint, every type's interactivity:

```sql
-- V12__save_item_state.sql (sketch)
create table save_item_states (
    save_id     uuid not null references saves (id) on delete cascade,
    user_id     uuid not null references profiles (user_id),
    item_path   text not null,   -- 'exercises[2]', 'items[0]', '' for whole-save state
    state       jsonb not null,  -- {"done": true} / {"rating": 4} / {"progress": "ep 3"}
    updated_at  timestamptz not null default now(),
    primary key (save_id, user_id, item_path)
);
```

- **Identity is the array index.** Safe *because* `structured_data` is
  immutable — no reprocess path means `exercises[2]` today is `exercises[2]`
  forever. If a reprocess path is ever built, this is the constraint it
  breaks first; that migration pays for content-hash identity then, not now.
- **Write is a full-row upsert** (`on conflict ... do update set state = excluded.state`),
  never a merge into the jsonb — same replace-don't-accumulate rule as
  digests and the shopping-list fold.
- One endpoint: `PUT /v1/saves/{id}/items/{path}/state` (or
  `PATCH /v1/saves/{id}/item-state` with path in the body — pick one, the
  point is *one*, shared by watch status, exercise ticks, checklist ticks,
  reading progress). `GET /v1/saves/{id}` embeds the user's states so the
  detail screen needs no second fetch.
- App side: extend `SavesProvider.patch`-style optimistic updates;
  `DetailObject` gains an optional `statePath` so `ObjectCards` can render a
  tick/rating control without knowing the type.

### 4.2 Behaviors per type, all pure client + the mechanism above

| Type | Behavior | Notes |
|---|---|---|
| `recommendation_list` | mark watched/read, personal rating | state `{done, rating}` per item |
| `checklist` | tick items, progress bar | count of done/total — derived in the client, never stored |
| `workout` | mark exercise complete; rest timer | timer reads the extracted `rest` string; parse leniently ("90s", "2 min"), fall back to a manual timer when unparseable |
| `course` | section done, "continue where I left off" | feeds the existing Continue rail with real data |
| `recipe` | cook mode (step-at-a-time, screen-wake via `expo-keep-awake`); serving scaling | scaling parses `quantity` strings — scale only what parses cleanly ("250g" → "375g"), pass through what doesn't ("a pinch"); never a model call |
| `place` / `itinerary` | Open Maps deep link | pure URL construction from `name`+`address`/`area` |

**Explicitly not in Phase 4:** any behavior needing a new AI call (that is
Phase 5 or never), delete/share pickers (same reasoning as the Library
redesign), and push-notification reminders (needs infra that doesn't exist).

### 4.3 Order of work

1. V12 + endpoint + provider plumbing (the mechanism).
2. Checklist ticks (smallest full loop, proves the mechanism).
3. Watchlist watched-state + rating (the flagship).
4. Workout completion + rest timer, recipe cook mode + scaling.
5. Maps deep links (an afternoon, no state involved).

---

## Phase 5 — derived intelligence (inside the cost classes, or not at all)

**Landed 2026-08-07**, in the order this doc's own "suggested overall sequence"
lays out (5.2 → 5.3 → 5.1) — see CLAUDE.md for the full verification record.
Three legal cost classes; every idea must name its class before it's built.

### 5.1 Same-call tokens — extend extraction schemas with *labelled* derived fields

Add to existing types in the one classify call, under names that read as
estimates:

- `workout.estimatedDurationMin`, `workout.estimatedDifficulty` — the prompt
  must say "estimate from the routine; this is your judgement, not the
  creator's claim". Distinct from `duration`/`difficulty`, which stay
  only-if-stated. The UI renders estimates with a visual marker (the same
  "distinguishable" rule CLAUDE.md's principle section requires).
- `recommendation_list.suggestedOrder` — only when `orderMatters` is 'no' and
  the model can justify one from the reasons given.
- `recipe.estimatedNutrition` — **not built, per this doc's own instruction.**
  Nutrition numbers read as facts, get eaten against, and a wrong estimate has
  real-world consequences the difficulty estimate doesn't.

Cost: tokens only. Both new fields landed and were confirmed against the real
API by the existing `KnowledgeTypeRegistrySchemaLiveTest` pre-flight — the
15-branch `anyOf` (workout's two new fields plus recommendation_list's third)
was accepted (HTTP 200), and on this run the sample content (`orderMatters:
'no'`) came back with a populated `suggestedOrder`, so the field's actual
shape is confirmed, not just its schema validity. **App-side, an estimate is
never rendered under the real field's label** — `detailModel.ts`'s workout
case only shows "Est. duration"/"Est. difficulty" when the stated
`duration`/`difficulty` is `[unclear]`, each suffixed literally "(estimated)";
`suggestedOrder` renders as its own "Suggested order (estimated)" chip row,
shown only when `orderMatters` is not `'yes'` — even if the model filled the
field in anyway, the UI suppresses it, since a prescribed order must never
share a label with a guessed one.

### 5.2 Local compute — free, do liberally

- **Workout total volume and an estimated session length** — `workoutLoadField`
  in `detailModel.ts`, pure arithmetic over the exercises the model already
  extracted (parses a leading integer from `sets`, a lenient duration from
  `rest`). The estimate only appears when `duration` itself is `[unclear]` —
  no point guessing at what the creator already said — and is always suffixed
  "(est.)" for the same distinguishability reason as 5.1's fields, even though
  nothing here is a model call.
- **Checklist/course/watchlist progress percentages** — `checkProgress`
  (renamed from the Phase 4 checklist-only version) now covers both
  `control: 'check'` and `control: 'watch'` objects, since both key their done
  state the same way (`state.done === true`). `recommendation_list`'s Items
  field gained a `progress` the same way checklist/course already had one;
  checklist/course themselves are unchanged, and a regression test pins that.
- Serving scaling — already Phase 4, unchanged.

### 5.3 Enrichment + embeddings — existing machinery, new consumers

- **Related saves**: `SearchService.relatedTo` — a correlated-subquery variant
  of the existing `semanticCandidates` query (same `<=>` operator, same
  `maxSemanticDistance` cutoff from `SearchProperties`, so the "0.40, provisional"
  caveat on that number still applies unchanged), scoped to the *viewer's*
  saves rather than the source save's owner — the useful reading of "you also
  saved" for a save shared into a Space, and the safer one, since it can never
  surface a co-member's own private saves. `GET /v1/saves/{id}/related`
  authorizes via the same `SaveService.getForUser` every other `/{id}/...`
  endpoint uses. App-side: `RelatedRail` on `SaveDetailScreen`, fetched lazily
  once a save is `ready`, rendering nothing on an empty result rather than a
  loading or error state — an empty array is the server's honest "nothing
  cleared the cutoff," the same rule full search already follows. **Not yet
  exercised against a real Postgres** — no local instance exists, and unlike
  the V11 jsonpath work this didn't get a live read-only probe before landing,
  because it touches no migration and reuses `semanticCandidates`' already-verified
  query shape verbatim; that substitution is reasoned, not measured.
- **Per-item enrichment for `recommendation_list`**: `RecommendationListEnricher`,
  capped at the first 10 items whose `kind` reads as film/TV/anime (a
  books-and-games list spends nothing), one `/search/multi` lookup each,
  `TitleMatch`-guarded exactly like `TmdbEnricher`. Adds only `posterUrl` —
  never touches `reason`, `name`, or any other field the creator's content
  produced. This needed a real extension to the shared merge mechanism:
  `EnrichSaveHandler.gapsOnly` previously only ever added or replaced a whole
  top-level field, which cannot express "add one new key to item 3 of an
  existing 5-item array" without either overwriting the array (risking a
  dropped or reordered item) or refusing to touch it at all (the array is
  non-empty, so the old "is it a gap" check would just skip it). The fix
  generalizes rather than special-cases: when both the current and found
  values are same-length lists, `mergeItemLists` merges per item by array
  index (safe for the identical reason Phase 4's item-state mechanism already
  relies on that precondition — enrichment runs once, entirely before the
  save is ever `ready`), keeping every existing key and adding only what was
  a gap. A regression test (`EnrichmentMergeTest`) pins that a same-length
  plain string array (TMDB's `genre` against a movie's own non-empty genre
  list) still falls through unchanged rather than being mistaken for an
  object array. **Mocked, like every other enricher here** — `TmdbEnricherTest`'s
  own caveat about not knowing whether the real API answers this way applies
  identically to `/search/multi`, which has never been called with a real key
  (no `WEAVR_TMDB_API_KEY` is configured in this environment).

### Not Phase 5, not any phase

- A second generation call per save, for anything.
- Cross-save "AI suggestions" fed by a scheduled job — same shape as the
  digest-scheduler decision: nothing generates until someone is looking, and
  nothing new draws on the 500 RPD pool without an explicit budget line in
  CLAUDE.md's request-budget section.

---

## Suggested overall sequence

```
3.1 recommendation_list ─┐
3.2 checklist            ├─ one schema pre-flight for the batch
         │               │
4.1 item-state mechanism ┘
4.2 checklist ticks → watchlist state   (mechanism proven on real types)
3.3–3.5 itinerary, course, github_repo  (registry work, parallelizable)
4.2 remaining behaviors (workout timer, cook mode, maps)
5.2 local compute → 5.3 related saves → 5.1 labelled estimates
```

The dependency that matters: **4.1 before any behavior**, and
**3.1/3.2 before 4.2** so the mechanism lands against real types instead of
hypothetical ones. Everything else can interleave with normal feature work.

---

## What comes after

All phases above landed 2026-08-07. The next roadmap is
[knowledge-collections.md](knowledge-collections.md) — merging per-source
objects into per-topic collections (three romance-anime Reels → one
watchlist), built as a derived layer over saves inside the same ground rules
this doc established.
