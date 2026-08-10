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
   cost zero AI. **Held again on 2026-08-10**, when S3 and S4 shipped the last
   of the derived surfaces: pins, entity discussion and the shared shopping
   list all landed with no model call, and the tab reads as a workspace
   without a sentence of generated prose on it.
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
| Recent discussion | latest `save_comments` on the Space's saves (table exists, V7), plus S3's `entity_comments` interleaved into the same block | zero AI ✅ |
| Travel "Budget ₹95,000" | best-effort local parse-and-sum of itinerary `cost` strings, labelled "(est.)" | zero AI |
| Travel "Missing: JR Pass, Visa" | unticked items of the Space's `checklist` saves | zero AI |
| Recipes + shared shopping list | Space-scoped collection + a per-Space list (S4, real schema work) | zero AI ✅ |
| Workout "Current program" | a *pinned* save (S4) — never an AI-merged program (synthesis, banned per the collections doc's shape 3) | zero AI ✅ |
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

  **✅ Decided 2026-08-10, as recommended.** Global states are shown, and the
  app states it in two places rather than one: the People tab carries the full
  sentence ("including things you marked from your own library" — the
  consequence, not just the fact), and every surface that actually *renders*
  someone else's status repeats the short form, because a disclosure one tab
  away from the disclosure-worthy thing is one nobody reads. The escape hatch
  stays exactly one column and one `where` clause away — `SpaceKnowledgeService
  .memberStates` is the single read to filter — and no consumer would change.
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

  **✅ Built 2026-08-10 as `V17__entity_comments.sql`** — ahead of the usage
  signal that gate asked for, and the gate is worth restating rather than
  quietly dropping: nothing has yet shown that save-comments-in-overview is
  insufficient, because nothing has used it. What made building it now
  defensible is that the *reason* for it is structural rather than
  preferential — a save comment about Blue Box dies when the Reel that carried
  it leaves the Space, which is a correctness problem, not a taste one. The
  usage question that remains open is whether people use it, not whether it is
  in the right place.

### Pins and per-Space structures (S4)

> **✅ Landed 2026-08-10 as `V18__space_pins_and_shared_lists.sql`.** Both halves
> shipped as sketched, and the section below is the original plan — what
> actually got built, and the four decisions that only surfaced while building
> it, are in the S3/S4 phase entry further down.

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

> **⏸ On hold as of 2026-08-10, explicitly, and the prerequisite below stands
> unchanged.** S3 and S4 shipped without it and the Overview is not poorer for
> it — which is the cost table above being right: the watchlist, the member
> progress, the discussion, the pins and the shared list are the whole of what
> makes that tab feel alive, and every one of them cost zero AI. Nothing here
> has been softened to make picking it up later easier. The budget line comes
> first, or S6 does not exist. See also [the request budget](../CLAUDE.md#the-request-budget-500-rpd-is-the-whole-apps-daily-ai-capacity):
> the Flash pool is **20 RPD**, not 250, so a per-Space summary has to be
> budgeted against the primary 500 and nothing else.

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
S1  space-scoped collections         ✅ landed 2026-08-10
S2  Overview v1: merged list + states + stats + discussion-from-comments
                                     ✅ landed 2026-08-10
S3  entity comments                  ✅ landed 2026-08-10 (V17)
S4  pins · shared shopping list      ✅ landed 2026-08-10 (V18)
S5  from-your-library suggestions    (depends: K1 personal + S1)
S6  AI summary + novel suggestions   (budget line first, or not at all)
```

> **Migration numbering in S3/S4 above was stale and is now resolved.** They
> named `V15__entity_comments.sql` and `V16__space_pins.sql`; V15 (`sync`) and
> V16 (`idempotency`) both landed in the meantime, so they shipped as
> `V17__entity_comments.sql` and `V18__space_pins_and_shared_lists.sql`. S1 and
> S2 needed no migration at all — both are read paths over tables that already
> exist, which is most of why they were cheap.

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
- **S1 — server merge. ✅ Landed 2026-08-10.** `CollectionService.treeOf` /
  `entitiesOf` take the save set to merge over; `SpaceKnowledgeService` (new,
  in the `space` package) owns the membership guard and the Space-shaped reads;
  `GET /v1/spaces/{id}/collections` and `.../collections/{nodeId}` are the two
  endpoints. `SaveFacts` gained a nullable `ownerId` and
  `CollectionEntity.Source` a nullable `addedBy`, both null on every personal
  read.
- **S2 — Overview v1. ✅ Landed 2026-08-10.** `GET /v1/spaces/{id}/knowledge`
  carries the group-scoped tree, the batched all-member states rolled up per
  member, and the discussion block. The disclosure decision is made above.

  **What shipped, and the six calls made while building it:**

  - **The doc said "a scope parameter"; what it actually needed was two static
    entry points and a new service.** `CollectionService` is an
    `@Transactional` bean that loads the *caller's* saves and joins the
    *caller's* state — a Space scope is not a parameter to that, it is a
    different question. So the merge core got two pure, static, database-free
    entry points (`treeOf`, `entitiesOf`) and everything Space-shaped lives in
    `SpaceKnowledgeService`, beside the guards it has to run first. The `space`
    package now depends on `collection`, never the reverse.
  - **A Space-scoped merge applies no `CollectionOverrides`, and that is a
    decision, not an omission.** Overrides are one *user's* curation
    (`collection_overrides.user_id`); reshaping a shared view by one member's
    private renames and merges would show the group something only that member
    asked for. Passing the caller's through is a one-line change nothing else
    would have caught, so it is pinned by a named test. A per-Space override
    table is the thing to build if this is ever wanted.
  - **`doneCount` means something different on a Space node, deliberately.**
    Everywhere else it is the caller's; here it counts entities *anyone* in the
    Space has finished — "3 of the 7 have been watched" is the group fact an
    Overview is asking for, and a viewer-scoped count under a group heading
    reads as a claim about the group. That is exactly why S0 refused to show a
    count at all, and this is what makes it showable.
  - **`done` staying canonical through K7 is what made S2 two functions long.**
    Every status writes `status` *and* `done`, so "has this member finished it"
    never has to know the watchlist vocabulary, and a row written before
    statuses existed still counts. The rollup and the group done set are the
    only two rules, both static, both mirrored client-side in
    `@/spaces/spaceProgress`.
  - **The app fetches only what it cannot derive.** The Overview's stats and
    collection rows still come from `@/local/derived` over saves already in the
    store (instant, offline, no request); `getSpaceKnowledge` supplies member
    progress, discussion and the group done counts, and a failure removes those
    two sections rather than the tab. Same for the entity list: derived locally
    first, replaced by the server's answer when it lands — and the viewer's own
    `state` is always taken from the store even after the swap, or a status flip
    bounces back to its old value for as long as an in-flight fetch takes.
  - **The collection row became tappable, which is the whole of what S0 was
    waiting for.** It routes to `/space/[id]/collection/[nodeId]` — nested under
    the Space, not the Library's `/collection/[nodeId]`, because the same node
    id means two different sets in the two places. The route pushes onto itself
    for a child folder, so the stack is the breadcrumb trail, and a node with
    folders lists its folders *instead of* its titles (K6's no-listing-twice
    rule, which the first pass of this screen broke and a screenshot caught).

  **Verified:** backend suite green (**441/441**, 6 opt-in skipped — 11 new
  tests: 5 in `CollectionServiceTest` for the scoped merge, `addedBy`
  attribution including the doc's own pinned two-member regression, and the
  no-overrides rule; a new `SpaceKnowledgeServiceTest` covering the rollup
  rules against the doc's own fixtures — an entity three members hold in three
  states, a pre-K7 bare `done`, a Space with no states, a missing display
  name). App typechecks including under `--noUnusedLocals` (no new findings —
  the two that remain are pre-existing), `expo export` clean for web and
  android with the web bundle still free of `openDatabaseAsync`/`expo-sqlite`/
  `enableChangeListener`, **29 node-standalone assertions** over the real
  shipped `spaceProgress.ts`/`spaceOverview.ts`/`merge.ts`, and **28 CDP
  checks** driving the real app on `expo start --web` with `USE_MOCK_DATA`
  flipped on locally (restored to `false` afterwards): both new Overview
  blocks, the disclosure in both places, the row tapping through, the drill-down
  Recommendations → Anime → Romance, "Sam: Best enemies-to-lovers arc" beside
  "You: Underrated gem" unblended, "Watched · Sam" and "Watching · Ana" on the
  same title, and a status cycled by a real synthetic click. **Not run on a
  device**, and **`GET /v1/spaces/{id}/knowledge` has never been called over
  HTTP** — the two SQL queries behind it are reasoned about and reviewed
  against the existing indexes (`saves_space_created_idx`,
  `save_comments_save_idx`, `space_members`' PK), not executed.
- **S3 — entity comments. ✅ Landed 2026-08-10.** `V17__entity_comments.sql` +
  `EntityCommentService` (in the `space` package, beside the guards it has to
  run first), three endpoints on `SpaceController`, and an inline thread on
  each row of the Space-scoped collection screen.
- **S4 — pins and the shared shopping list. ✅ Landed 2026-08-10.**
  `V18__space_pins_and_shared_lists.sql` + `SpacePinService`, plus
  `shopping_lists.space_id` and a `ShoppingListService` that takes a scope.

  **What shipped, and the seven calls made while building them:**

  - **Space-scoped comments beside global states, deliberately, and the two
    tables answering the same-looking question differently is the design.**
    `entity_states` (V13) is per `(user_id, entity_key)` with no Space
    dimension, and S2's disclosure decision leans on exactly that: completing
    Your Name is a fact about the person, so it shows wherever that entity
    appears. A *remark* is not like that — it was said in a room, to the people
    in it, and carrying it into another Space that happens to hold the same
    entity would disclose a conversation rather than a status. So `space_id` is
    part of V17's key and is absent from V13's.
  - **`entity_key` gets no foreign key, and cannot have one.** An entity is
    derived — `Entities.key` over whatever the Space's saves merge to today — so
    there is no row to reference and nothing to validate against on write.
    Validating against the derived set would make commenting cost a full merge
    *and* still be a race. The bounded consequence is stated rather than
    hidden: a comment outlives its entity (every save mentioning it leaves the
    Space) and simply stops being reachable, exactly as a
    `collection_overrides` row does.
  - **One discussion block, not two.** The Overview interleaves save comments
    and entity comments by time and caps them together, because "what is being
    talked about in here" is one question — splitting the block by which table a
    remark landed in would ask the reader to care about a storage detail.
    `SpaceComment` grew a nullable `entityKey`/`entityName` beside its nullable
    `saveId`; exactly one is set. **An entity comment's row is deliberately not
    tappable**: an entity key does not name a collection node, so routing
    somewhere would be a guess, and the rule S0 followed for the inert
    collection row applies unchanged.
  - **The comment count is batched into the entity list, never fetched per
    row.** Same reasoning as `memberStates` beside it and
    `statesForSaves` before that: N is the size of a merged collection, which
    grows with the Space and is bounded by nothing. The thread itself is one tap
    and one request away, and is not fetched until it is opened.
  - **A pin is the honest version of the vision's "Current Program", and that
    is the whole argument for the table.** The tempting implementation merges
    several push-day saves into one routine — the synthesis
    [knowledge-collections.md](knowledge-collections.md) rules out as shape 3,
    whose failure mode is somebody in a gym following a program no human wrote.
    A pin is a member pointing at one save and saying "this one". Identical row
    on the screen; only one of the two can be wrong in a way that matters. It is
    also the one thing on the Overview that genuinely *cannot* be derived —
    everything else falls out of saves and states that already exist.
  - **The pin's label is resolved on read, never stored.** A label copied in at
    pin time goes stale the first time a note is renamed, and a pin that lies
    about what it points at is worse than no pin. Same read resolves
    `available`, and an unavailable pin is **shown** rather than filtered:
    hiding it would leave a row nobody can reach while the unique index still
    holds its subject.
  - **The shared shopping list needed no change to `fold` at all.** A line has
    stored each contributing recipe's own quantity, and recomputed the total,
    ever since a re-delivered job silently took garlic from 7 cloves to 10. That
    property was built for retries and pays for sharing for free: two
    *people's* recipes merge by exactly the rule two of one person's always
    did. What did have to change is scope — V5's "at most one open list per
    user" index would have forbidden a user holding a personal list and a
    Space's at once, so it is now two partial indexes, one per scope.
  - **`on delete cascade` on `shopping_lists.space_id`, and the alternative is
    worse than it looks.** `set null` would turn a deleted Space's shared list
    into a *second* personal open list for whoever created it — which the
    partial unique index rejects, so deleting a Space would fail on a
    constraint violation from a table nobody deleting a Space is thinking about.
  - **The Act's target follows the save, not the caller.** A recipe in a Space
    feeds that Space's list however many members convert it, which is what keeps
    the per-(list, save) idempotency holding across people as well as across
    retries. The *cap* still follows the caller, so `actorId` now rides in the
    job payload — with a fallback to the save's owner, because a queue is not
    drained the instant it is written to and a deploy must not lose the
    conversions already in flight.

  **App-side, three things generalise beyond this feature:**

  - **The Space's shared list is an on-demand read the screen holds itself, not
    a second scope in the local store.** Adding one would have meant a
    `SCHEMA_VERSION` bump, and L5 established that a bump is only for a change
    the local data cannot survive — never for one it can re-fetch — because a
    bump drops the `outbox` and with it writes the server has never seen. The
    *ticks* still go through the queue, so the offline guarantee that matters
    (a tap made in a shop is delivered) holds either way; what a shared list
    gives up is painting before the network answers.
  - **The item mutation is the personal one, unchanged, on both sides.** An item
    id addresses exactly one row on exactly one list, and the server proves
    access per statement — one `exists` widening the existing `where`, never a
    row loaded and then judged. So there is no Space variant of
    `PATCH /v1/shopping-list/items/{id}` anywhere. `DELETE .../checked` *is*
    scoped, and that asymmetry is deliberate: one button that cleared both lists
    would be unrecoverable.
  - **A Recipe Space still defaults to Sources, not Overview.** `spaceDefaultTab`
    opens on Overview only when the saves *merge* into a collection, and recipes
    are shape 2 — they never produce one. Deliberately not widened to "or it has
    a shared list": that fact arrives over the network, and a default tab that
    changes a frame later moves the tab strip under the user's thumb, which is
    the reason S0 computed it from local data in the first place.

  **Verified:** backend suite green (**455/455**, 6 opt-in skipped — 14 new
  tests: `EntityCommentServiceTest` and `SpacePinServiceTest` mirror
  `SaveItemStateServiceTest`'s mocked-`JdbcClient` style for the same
  no-local-Postgres reason, covering the membership guard reaching the database
  never, the owner-vs-author delete branch, the tombstone audience being the
  Space's members rather than the deleter, the editor bar on both pin writes,
  and the save-pin check that a collection pin must *not* run;
  `ConvertToShoppingListHandlerTest` gained the actor-resolution pair). App
  typechecks, `expo export` clean for web and android with the web bundle still
  free of `openDatabaseAsync`/`expo-sqlite`/`enableChangeListener`.

  **Both migrations were executed against the live schema inside a transaction
  and rolled back — 30 checks, all passing** ([docs/testing.md](testing.md#verifying-a-migration--run-it-against-the-real-schema-and-roll-it-back)).
  Not just "it parsed": every object, index, trigger and policy asserted from
  the catalog; every query the two new services and the widened Act guard
  actually issue, run against the new shape; `explain` confirming the thread
  read uses `entity_comments_space_entity_idx`; and the three constraints the
  migration *is* exercised rather than inventoried — a personal and a Space list
  open at once, a second of either rejected, and the pin upsert returning the
  same row twice. **One probe finding worth carrying:** the "old index is gone"
  check was written as a name lookup and passed for the wrong reason, because
  V18 *reuses* the name for the narrowed index. Asserting on `indexdef` is what
  makes it a real check — and Postgres normalises the predicate to upper case,
  so it has to be `ilike`.

  **57 CDP checks driving the real app**, with `USE_MOCK_DATA` flipped on
  locally (restored to `false` afterwards): a thread opened on Blue Box from
  three folder levels down, a remark typed and sent, the collapsed row's count
  following it with no refetch, and the same remark appearing in the Overview's
  discussion block attributed to the *entity*; a source pinned from Sources,
  leading the Overview as "Pinned by You", surviving a round trip; a collection
  pinned; and the full shared-list flow — two members' recipes converted from
  their own detail screens, the section appearing only once it had something on
  it, the Space-scoped route, both recipes on one list, an item ticked, and none
  of it touching the personal list.

  **Two probe findings, both new:**

  - **The run has to be one page load.** `mockRepository` is module-scope state
    that resets on reload (its own doc says so, and that is the right lifetime),
    so a `Page.navigate` between a write and the read that checks it discards
    exactly the thing under test. Every earlier probe in this repo could reload
    freely because it checked the local *store*, which persists. So this one
    moves the way a user does — tap a card, tap a tab, tap back — and re-enters
    a Space from the Spaces list whenever a screen's own `load()` needs to run
    again. That makes each navigation an assertion too.
  - **The mounted-pane trap has a second form, and it is worse.** The shell
    keeps Home/Library/Spaces mounted *and* a pushed route stacks on top, so
    several elements share a label and all but one are invisible — a probe that
    takes the **first** match clicks a hidden pane and fails as "the tap did not
    work", which sends you debugging the handler. Filter to elements with a
    non-empty `getClientRects()` and take the **last**. Also confirmed on a
    second attribute: React Native Web emits no `aria-checked` for
    `accessibilityState={{ checked }}`, any more than it emits `aria-selected` —
    so a tick has to be asserted from rendered content.

  **Not run on a device**, same standing caveat as the rest of the app's UI
  work. **`GET /v1/spaces/{id}/entity-comments`, `.../pins` and
  `.../shopping-list` have never been called over HTTP** — their SQL was
  executed by the probe above, but the controllers themselves are reviewed, not
  exercised.
- **S5** as described; independent, shippable alone.
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

1. ~~The state-disclosure question (S2, above)~~ — **answered 2026-08-10**, as
   recommended: global states are shown and said, in the People tab and again
   on every surface that renders someone else's status. Detail in the S2
   section above.
2. Does Overview need per-collection tabs *within* a Space sooner than
   expected? Watch the Travel template — itinerary + checklist + places is
   the likeliest multi-collection Space. **S4's collection pin is the cheap
   half of an answer**: a Space with several collections can now say which one
   leads, which may be enough that the tab split never has to happen.
3. `save_duplicates`' merge-prompt flow: once entities absorb duplicate
   *presentation*, is the suggested-merge UI still worth its screen space, or
   does it retire into the entity's sources list? Decide on real usage, not
   now.
4. **Does an entity comment need a way back to the thing it is about?**
   (Opened by S3.) The Overview's discussion block renders an entity comment
   un-tappable, because an entity key does not name a collection node and
   routing anywhere would be a guess. Resolving it means either storing the
   node id alongside the comment — which goes stale when the axis chain
   changes — or deriving "which node holds this key" on read, which is a whole
   merge for one row. Neither is worth building before anyone has missed it.
5. **Does a Recipe Space want its shared list on the Sources tab too?**
   (Opened by S4.) It lives on Overview, which a Recipe Space does not open on,
   because recipes are shape 2 and produce no collection to make Overview the
   default. Widening `spaceDefaultTab` to "or it has a shared list" was
   rejected — that fact arrives over the network and would move the tab strip
   after first paint — but a second entry point from Sources would not have
   that problem.
