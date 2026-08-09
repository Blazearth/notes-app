# Knowledge-first Spaces: from shared folder to shared workspace

This is the implementation plan for redesigning Spaces around knowledge
instead of saves. The problem, stated once: **a Space today is a folder of
saves with people attached.** Collaborators don't care that Aryan saved
Reel #17 — they care about the anime the group should watch, the program the
group is running, the trip the group is planning. The Space's primary surface
should be the merged, living knowledge built from everyone's sources; the
saves become evidence ("where did Blue Box come from?"), not the product.

Target mental model:

```
Raw saves (any member) → AI extraction → shared knowledge layer → collaboration
     16 saves                                1 watchlist, 32 anime, states, discussion
```

**This doc builds directly on [knowledge-collections.md](knowledge-collections.md)
and depends on its phases**: the entity key (`Entities.key`), the derived
merge (`CollectionService`, K1), and per-entity user state
(`entity_states`, K2) are the machinery here too — Spaces add a *scope*
(the Space's saves instead of one user's) and the collaboration surfaces on
top. Nothing below builds a second merge mechanism. Read that doc's
"What the vision cannot mean here" first; every constraint applies, plus
three that are Spaces-specific:

1. **The per-Space AI summary was already cut once, on purpose.** The
   2026-08-07 Spaces redesign considered a live Gemini summary per Space and
   removed it because it had no budget line (CLAUDE.md, Spaces-rebuild
   section). It does not come back as a side effect of this redesign. It
   comes back — if at all — as phase S6, digest-shaped, with its own budget
   line written into CLAUDE.md's request-budget section first. Meanwhile the
   Overview is designed to be ~90% *derived*, which the examples below show
   is most of its value anyway: the watchlist, states, stats and discussion
   cost zero AI.
2. **Merging never mutates saves, in Spaces exactly as anywhere else** — and
   V7's own rule stands: `save_duplicates` writes a *suggestion*, never a
   merge, because silently merging someone else's save is unrecoverable. The
   knowledge layer actually lowers the stakes here: when two members' Reels
   both mention Blue Box, both saves keep existing untouched and both appear
   as sources on one derived entity — the duplicate stops being a UI problem
   without anyone's save being touched.
3. **A Space's knowledge derives from the Space's saves only.** Never from
   members' personal libraries — the `relatedTo` scoping rule (a co-member's
   private saves must never leak) applies to every aggregate this doc adds.
   The one place personal data may inform a Space view is S5's suggestions,
   which are computed per-viewer over the *viewer's own* library and shown
   only to them.

---

## Information architecture

The proposed five-tab structure (Overview / Knowledge / Sources / People /
Activity) is right in spirit; four tabs is the right build:

```
Overview   — the knowledge workspace (new, becomes the default tab)
Sources    — today's Saves tab, renamed
People     — unchanged
Activity   — unchanged
```

- **Overview *is* the knowledge tab.** A separate Knowledge tab earns its
  place only when one Space routinely holds several unrelated collections
  (a watchlist *and* a workout program *and* recipes). Real Spaces are
  created from typed templates (Travel/Study/Movies/…), so most will have one
  dominant collection — in which case a Knowledge tab is Overview with the
  other sections removed. If multi-collection Spaces become common, Overview
  grows a per-collection section list and the tab split can happen then; the
  API (S1) already serves collections as a list, so that is a UI-only change
  later.
- **Saves → Sources is a rename worth doing everywhere it shows**, not just
  the tab label: it is the mental-model statement. App copy only — no API
  rename (`/v1/saves` stays; a payload-breaking rename buys nothing).
- Default tab becomes Overview for Spaces whose saves yield at least one
  merged collection; Spaces holding nothing yet (or only unmergeable types)
  default to Sources, so a brand-new Space doesn't open onto an empty
  dashboard.

### What each Overview section actually is (cost class per section)

| Section (from the vision) | What it really is | Cost |
|---|---|---|
| Watchlist with per-member status ("Watching by Aryan") | Space-scoped derived merge (K1) + members' entity states (K2) | zero AI |
| Statistics (7 anime · 3 completed · 2 watching) | count over the same two inputs | zero AI |
| Recent discussion | latest `save_comments` on the Space's saves (table exists, V7) | zero AI |
| Travel "Budget ₹95,000" | best-effort local parse-and-sum of itinerary `cost` strings, labelled "(est.)" | zero AI |
| Travel "Missing: JR Pass, Visa" | unticked items of the Space's `checklist` saves | zero AI |
| Recipes + shared shopping list | Space-scoped collection + a per-Space list (S4, real schema work) | zero AI |
| Workout "Current program" | a *pinned* save (S4) — never an AI-merged program (synthesis, banned per the collections doc's shape 3) | zero AI |
| "From your library: 3 anime not on this watchlist" | per-viewer diff against the viewer's own entities (S5) | zero AI |
| AI summary sentence | S6, digest pattern, budgeted or absent | 1 gen call, cached |
| "AI suggests: Horimiya…" (novel items) | S6-or-never — novel recommendations are generative | gen call, budgeted |
| "AI says: increase bench next week" | never — coaching synthesis, and wrong advice has gym-floor consequences (the `estimatedNutrition` refusal, same reasoning) | — |

The load-bearing observation: **everything that makes the Overview feel like
a living workspace is derived.** The two genuinely generative rows are
garnish, which is why they can wait for a budget line without gutting the
feature.

---

## Server-side design

### Space-scoped collections (S1)

`CollectionService` (from K1) gains a second entry point that takes the save
set to merge over instead of assuming "the caller's ready saves":

- `GET /v1/spaces/{id}/collections` and
  `GET /v1/spaces/{id}/collections/{type}?facet=…` — same derived shapes as
  the personal endpoints, computed over the Space's ready saves. Membership
  check first (`SpaceService`'s existing guard), then merge; every save in
  the input set is already member-visible, so the merge introduces no new
  access question.
- Each merged entity's `sources[]` gains `addedBy` (the save's owner) — this
  is the "saved by Maya" attribution, and it comes free from the saves
  already carrying `user_id`.
- Derived per request, never stored — a Space's collection has the same
  staleness surface as the personal one plus membership changes (a member
  leaves and their saves leave the Space), which a stored tree would also
  have to invalidate on. The derive-don't-store argument is *stronger* here,
  not weaker.

### Shared progress (S2) — a read path, not a new write path

`entity_states` (V13, K2) is per `(user_id, entity_key)` with no Space
dimension, and that is the right storage: Rahul completing Your Name is a
fact about Rahul, not about any Space. The Space Overview needs a batched
read: states of *all members* for *the entities the Space's saves produce*.

- `SpaceEntityStates.forSpace(spaceId)` — one query joining
  `space_members` × `entity_states` filtered to the derived entity-key set,
  returned as `entityKey → [{userId, state}]`. Batched like
  `statesForSaves` (one query per screen, never N+1).
- **The disclosure question this creates must be answered, not slid past:**
  a member who watched Blue Box *privately* (state written from their own
  library) would appear as "Completed by Rahul" in any Space whose watchlist
  contains Blue Box — the state is global, the surface is shared. That is
  arguably the feature (collaboration is for sharing progress) and arguably
  a leak (personal watch history disclosed by key collision). **Decision
  needed at S2, recommendation: global states are shown, with one line in
  the People tab's UI copy stating that watch status is shared in Spaces —
  and if that feels wrong in use, the escape hatch is a `shareStates`
  per-member flag on `space_members`, filtering the read.** What we do not
  do is fork state per Space — "watched here but not there" fragments the
  single fact the entity layer exists to keep whole.
- Rollups ("Maya: 5/12 watched") are computed in the same pass. Workout
  per-member progress reads `save_item_states` (workout state stays
  save-keyed, per the collections doc) for the pinned program's save —
  "Week 2 / Week 5" style program-position tracking does not exist anywhere
  and is **not** invented here; per-exercise completion is what we have and
  what ships.

### Discussion (S3) — reuse, then extend only if pulled

- **First: surface what exists.** `save_comments` + the sparse
  `space_activity` feed already carry real discussion; Overview's "Recent
  discussion" is the latest N comments across the Space's saves (one indexed
  query), each attributed and tap-through to its save. Zero schema.
- **Entity-level comments** ("Blue Box starts slow…" attached to Blue Box,
  not to whichever Reel mentioned it) are the better long-term home, but they
  need a new table (`V15__entity_comments.sql`: `space_id`, `entity_key`,
  `user_id`, `body` — comments are *space-scoped*, unlike states, because a
  remark to your friends belongs to that room). Build only after S1–S2 ship
  and real usage shows save-comments-in-overview isn't enough — the V14
  overrides rule, applied to comments.

### Pins and per-Space structures (S4)

- `V16__space_pins.sql` (or fold into an existing table if it stays this
  small): `(space_id, kind, subject, payload)` — pin a save as "current
  program", pin a collection to the top of Overview. Explicit member action,
  `editor`+ role, activity-logged. This is the honest version of the
  workout example's "Current Program": a human chose it; the AI didn't
  synthesize it.
- **Shared shopping list is real schema work, flagged as such:**
  `shopping_lists` (V5) is one open list per *user*. A Recipe Space wants one
  per *space*, fed by the same Act. The clean shape: `shopping_lists` gains a
  nullable `space_id` (null = personal, as today), the Act targets the Space
  list when converting from a Space context, and `fold` (already static and
  per-contribution) works unchanged — per-save contributions were designed
  for exactly this kind of reuse. Cap interaction: the Act's weekly cap is
  per *user* (the converter's caller), unchanged.
- Cooking schedule ("Saturday: Lasagna") — a pin with a `payload: {date}`,
  not a new calendar feature. If it outgrows that, it earns its own design.

### Suggestions without AI (S5)

Per-viewer, computed over the *viewer's own* library only (constraint 3):
diff the viewer's personal entity set (same `kindNamespace`) against the
Space's — "You have 3 anime on your own watchlist that aren't in this Space —
add them?" Adding shares the *save* into the Space through the existing
add-to-space flow, so the normal sharing consent moment is preserved: nothing
enters the Space without the owner tapping. This is the safe, free 80% of
"AI suggests" — it recommends from what the group's own members already
vetted, and it is private until acted on.

### AI summary and novel suggestions (S6 — budgeted or absent)

Digest pattern, verbatim: generated only when someone opens Overview, cached
per `(space_id, content_hash of the source save-id set + state counts)`,
regenerated only when the hash moves, plain-text out (`summarizeDigest`'s
shape), `gemini_calls` row with null `save_id`, drawn from the 500 pool —
never the 20-RPD Flash pool. **Prerequisite, non-negotiable: a budget line in
CLAUDE.md's request-budget section with an estimate of summaries/day at
current Space counts.** Novel recommendations ("Horimiya") ride the same call
if it exists — one call returning summary + up to three suggestions, never
two calls — and render under an "AI suggests (not from your saves)" label so
they are never confused with member-vetted entries. If the budget line can't
be written honestly, S6 doesn't ship, and per the cost table above the
Overview loses two lines of garnish.

---

## Phases

```
S0  rename + tab scaffold            ✅ landed 2026-08-09 (app only, no server change)
S1  space-scoped collections         (depends: collections K1)
S2  Overview v1: merged list + states + stats + discussion-from-comments
                                     (depends: S1, collections K2)
S3  entity comments                  (only if S2 usage pulls for it)
S4  pins · shared shopping list      (independent of S3)
S5  from-your-library suggestions    (depends: K1 personal + S1)
S6  AI summary + novel suggestions   (budget line first, or not at all)
```

- **S0 — rename and scaffold. ✅ Landed 2026-08-09.** Sources tab label + copy
  sweep, Overview tab added rendering only what needs no new server work:
  stats over the saves the Space screen already fetches, and the
  recent-comments block if the detail payload already carries comments (else
  it waits for S2). Ship the frame early so the IA change gets real use before
  the heavy sections land. Verification: headless Chrome, `USE_MOCK_DATA`, the
  CDP click-through recipe (docs/testing.md) — tab switching, default-tab
  logic for empty Spaces.

  **What shipped, and the four calls made while building it:**

  - **The Space's collections are derived client-side, not fetched.** The app
    has been deriving `GET /v1/collections` locally since L2 (`@/local/derived`
    over `@/collections/merge`), so a Space-scoped collection is the same pure
    merge over a different save set — no request, no AI, no server change,
    which is exactly S0's constraint. S1 does not replace this so much as give
    the *server* the same capability (and with it `addedBy`, which the client
    cannot derive because a save's owner is not on `SaveResponse`).
    `@/spaces/spaceOverview.ts` is the model, following S2's own naming note
    and the `detailModel` convention.
  - **The recent-comments block waited for S2, per the doc's own condition.**
    The Space detail payload carries no comments — `listComments` is per-save,
    so surfacing "recent discussion" at S0 would have meant one request per
    save in the Space. Left out rather than approximated from the `commented`
    rows in the activity feed.
  - **No done counts on the Overview.** `entity_states` is per
    `(user_id, entity_key)` and global, so the only count available without
    S2's batched all-members read is the *viewer's own* — and "1 watched" on a
    group's Overview reads as a claim about the group. Pinned by a CDP
    assertion that the word "watched" does not appear.
  - **The collection summary row is deliberately not tappable.** The merged
    entity list is S2's, and the existing `/collection/[type]` screen renders
    the viewer's *whole library*, so routing a Space's row there would show a
    different set of entities under the Space's heading. An affordance that
    goes somewhere wrong is worse than none; it becomes tappable when the
    Space-scoped list exists to receive it.

  **A mock-fixture bug found on the way, worth carrying forward:** no
  `MOCK_SAVES` entry carried a `spaceId`, while `mockRepository` held a
  separate `spaceSaves` map declaring which saves were in which Space. Every
  screen reading the local store (`readFeed({ spaceId })`, which filters on the
  save's own field) therefore saw every Space as empty — a shape the pipeline
  cannot produce, and a second source of truth that was silently wrong in one
  direction. Fixed by putting `spaceId` on the saves and deleting the map, so
  `listSpaceSaves` filters exactly like `SaveService` does. `sp-anime` was
  added as the fixture for a Space whose sources *merge* (the two overlapping
  `recommendation_list` saves, "Blue Box" in both), which is what makes both
  sides of the default-tab rule testable at all; `MOCK_SPACES`' `saveCount`s
  were corrected to match the fixtures rather than claiming 12 over a list of 2.

  **Verified:** app typechecks (including `--noUnusedLocals`, no new findings),
  `expo export --platform web` bundles clean, **29 node-standalone assertions**
  over `spaceOverview.ts` (compiled alone and executed, this repo's technique
  for logic with no test runner — covering the merge-not-sum count, distinctness
  across types, a nested collection whose entities all sit in a subgroup, both
  sides of `spaceDefaultTab`, and status filtering), and **26 CDP checks**
  driving the real app on `expo start --web` with `USE_MOCK_DATA` flipped on
  locally (restored to `false` afterwards): the rename on the list and the
  header, the four tabs in order, Overview opening by default on the merging
  Space and Sources on the two that don't, "3 titles · 2 sources" against 4
  items over 2 saves, tab switching in both directions, and the singular copy on
  a one-source Space. **Not run on a device**, same standing caveat as the rest
  of the app's UI work.

  **One probe finding worth reusing:** React Native Web does **not** map
  `accessibilityState={{ selected }}` on a `Pressable` to `aria-selected` —
  every `role="tab"` reports `null`, so "which tab is open" has to be asserted
  from rendered content, never the attribute. And the shell (`app/index.tsx`)
  keeps Home, Library and Spaces mounted as panes, so `document.body.innerText`
  on the Spaces tab also contains Library's "12 saves" and its collection
  cards' "3 titles · 2 sources" — the first pass of this probe passed *and*
  failed for reasons that had nothing to do with the screen under test. Scope
  every read: navigate straight to `/space/{id}` (its own route, clean
  document), and query list cards by their `aria-label`.
- **S1 — server merge.** The `CollectionService` scope parameter, two
  endpoints, `addedBy` attribution. Tests mirror `GroupServiceTest` plus one
  pinned regression: two members saving Reels that share an entity produce
  one entity with two sources and distinct `addedBy`. Verify live with curl
  against a real Space holding overlapping saves from two test users
  (insert the second user directly in `auth.users` per the standing
  never-sign-up rule).
- **S2 — Overview v1.** The batched member-states read, rollups, stats,
  discussion block. The disclosure decision above gets made and written
  down here. App model logic (`spaceOverviewModel.ts` or similar) follows
  the `detailModel` convention: imports only types, executed standalone
  under node with fixtures covering an entity three members hold in three
  different states, a memberless state (member left; render "by a former
  member", don't crash), and a Space with zero mergeable saves.
- **S3–S5** as described; each independent, each shippable alone.
- **S6** last, and only over the budget-line threshold.

## Explicitly out, and why

- **Changing ingestion.** "5 people save 16 things → the Space ends up with
  1 watchlist" is true *at the presentation layer only*. The pipeline still
  makes one save per source — that is what provenance, votes, comments, and
  item-state identity all hang off. The Space *ends up showing* one
  watchlist; it never stops containing 16 saves, and the Sources tab is
  where they live.
- **Write-time merge of saves, auto or AI.** V7's suggestion-only rule
  stands; the derived entity layer makes most duplicate pain moot anyway.
- **AI coaching / program synthesis** ("increase bench next week", a merged
  push program) — generative, unbudgeted, and consequence-bearing. The
  collections doc's shape-3 boundary, unchanged by the Space context.
- **A Realtime/live-updating Overview.** Supabase Realtime is explicitly not
  part of this backend's model (CLAUDE.md architecture note); Overview
  refreshes on focus like every other screen.
- **Roles beyond what `space_members` has.** Viewer/editor/owner already
  gate writes; pins need `editor`, nothing here needs a new role.

## Open questions (answer during S1/S2)

1. The state-disclosure question (S2, above) — recommendation recorded there;
   needs a real decision when the first shared watchlist renders.
2. Does Overview need per-collection tabs *within* a Space sooner than
   expected? Watch the Travel template — itinerary + checklist + places is
   the likeliest multi-collection Space.
3. `save_duplicates`' merge-prompt flow: once entities absorb duplicate
   *presentation*, is the suggested-merge UI still worth its screen space, or
   does it retire into the entity's sources list? Decide on real usage, not
   now.
