# Weavr

Save anything — a Reel, a TikTok, a screenshot, a link, a PDF, a voice memo — and get
back something structured you can act on. Share into the app from the OS share sheet;
a pipeline classifies the content, extracts structured fields, enriches it, embeds it
for semantic search, and surfaces a type-specific action (recipe → shopping list,
workout → routine, restaurant → navigate).

Built for the RevenueCat Shipaton 2026 (Aug 1 – Sep 30, 2026).

- [CLAUDE.md](CLAUDE.md) — architecture and the constraints that drive it
- [docs/implementation-plan.md](docs/implementation-plan.md) — the phase plan
- [docs/testing.md](docs/testing.md) — how to check work, and what each check does not prove
- [docs/local-first.md](docs/local-first.md) — the local store / sync / offline-writes plan (**complete, L1–L5 landed 2026-08-09**: reads come from a local store, writes drain from a durable outbox, sync is a windowed `GET /v1/sync`, and search answers from the device's own FTS index before the server replies)
- [docs/competitive-analysis.md](docs/competitive-analysis.md) — teardown and steal list
- [save-anything-app-spec.md](save-anything-app-spec.md) — original product spec (partly superseded)

## Layout

```
api/     Spring Boot — REST API and (from Phase 2) the ingestion/AI pipeline
app/     Expo (React Native + TypeScript) with an EAS dev client
docs/    Plan, spec, competitive analysis
```

Supabase is a managed **Postgres + Auth + Storage** host here, not the backend.
No Edge Functions, no Realtime-driven business logic — Spring Boot owns the API
surface, the RevenueCat webhook, entitlement gating, and the pipeline.

## Status — Phases 1–5 backend complete, Spaces + local-first + knowledge collections in; the app has run on a device once

### What's real vs. stubbed

| Area | State | Notes |
|---|---|---|
| Flyway `V1__init.sql` | **real, verified** | Applied against Supabase (PG 17.6). All 11 tables, pgvector, HNSW index, generated FTS column, RLS policies |
| Supabase JWT auth | **real, verified** | Issuer + audience checked explicitly. Both paths exercised: rejection (401) and accept, with a real **ES256** token |
| `POST /v1/saves` | **real, verified** | `202` with a real JWT; row committed and readable. Exercises `@CurrentUser`, the lazy profile upsert, and the JSONB mapping |
| `GET /v1/saves`, `/{id}` | **real, verified** | `200` on both; `/{id}` returns the created save, the list returns it too |
| Job queue | **real, verified** | Enqueue runs as part of the create path; the runner drains it |
| Job runner | **real, verified** | `SKIP LOCKED` claim, per-group exclusion, three error paths, stale-claim sweep. Claimed and completed a real job against live Supabase |
| Extraction cascade — metadata + captions | **real, verified live via RapidAPI; yt-dlp's own YouTube path stays blocked on Render** | `--dump-single-json` probe then `--write-auto-subs`, VTT to prose, error classification — proven against yt-dlp 2026.07.04 by `YtDlpLiveTest`, but from a residential IP. Render's datacenter IP hits YouTube's bot check on every yt-dlp probe; cookies and a player-client override were both tried against the live deploy and **both confirmed to fail** (8/8 real videos). `RapidYtClient` (2026-08-05) bypasses YouTube directly via RapidAPI instead, and **is** confirmed live: two real YouTube URLs completed the full pipeline on the real deploy on 2026-08-04. See below |
| Extraction cascade — ASR | **built, unverified live** | yt-dlp audio download → ffmpeg 16 kHz mono downmix → Groq Whisper. A different provider with its own key, so it costs no Gemini RPD. 9 unit tests, all mocked — no real Groq call made yet |
| Extraction cascade — link / PDF | **built, unverified live** | Readability4J for plain links, PDFBox for PDFs — the non-video branch, reached when yt-dlp has no extractor for the URL or it's a `.pdf`. 7 unit tests, all mocked — no real page or PDF fetched yet |
| `process_save` handler | **real** | Runs the cascade and parks the text in `save_stages` for `classify_save` to pick up |
| Gemini classify-and-extract call | **real, verified live** | `classify_save` handler + `GeminiClient`/`GeminiBudgetService`/`KnowledgeTypeRegistry`. Ran against the real API and produced real structured saves on 2026-07-30 — see below. 25 unit tests (`GeminiClientTest`, `GeminiBudgetServiceTest`, `KnowledgeTypeRegistryTest`, `ClassifySaveHandlerTest`) |
| OCR tier — frames, tesseract, voting, gate | **real, verified live** | `pipeline/ocr/`. Bounded worst-quality download → one ffmpeg pass cutting deduped keyframes into a colour and a grey branch → tesseract per frame (TSV, for per-word confidence) → vote across frames → quality gate. Driven end to end against real ffmpeg 8.0 and tesseract 5.5.0 by the opt-in `OcrLiveTest`, which needs **no network** — it renders its own fixtures. That run overturned two decisions the plan had specified; see below |
| OCR tier — Flash vision escalation | **built, unverified live** | `GeminiClient.transcribeFrames` sends the sharpest frames as base64 `inline_data` and gets back a verbatim transcription, which then flows through the ordinary classify call. Request shape is pinned by `MockRestServiceServer`; no real vision call has been made |
| Thumbnail / frame ranking | **real** | Variance-of-Laplacian, end-weighted, pure Java, no model call (CA#15). Used today to pick which frames a vision escalation carries; nominating a stored thumbnail needs a Storage path that does not exist yet |
| Embeddings | **real, verified live** | `embed_save` job runs after a save is already `ready` — being findable by similarity is an enhancement, not a precondition. `gemini-embedding-001` at an explicitly-requested 1536 dims, embedding a `label: value` profile built from `structured_data` rather than the raw caption (CA#8). The one Gemini call deliberately **not** behind `BudgetApproved`: its pool is separate, so it never spends a save's RPD |
| Search (FTS + vector, RRF) | **real, verified live** | `GET /v1/saves/search?q=` fuses Postgres full-text and pgvector with Reciprocal Rank Fusion (k=60), degrading to either half alone. Verified end to end against live Supabase: *"somewhere nice to eat in Denmark"* returns the Noma save on semantics alone — the text says Copenhagen, never Denmark |
| Acts — recipe → shopping list | **real, verified live** | `V5__shopping_list.sql` + `act/`. One open list per user; a recipe's ingredients are normalised by one Gemini call into products, quantities and supermarket aisles, then **merged** into what's already there. Verified against two real recipes: garlic came out as 7 cloves (3 + 4) and olive oil as 4 tbsp (2 + 2), grouped in shop-layout order |
| Acts — mobile | **real, never run on a device** | "Add to shopping list" on a recipe's detail screen, plus `/shopping-list` with optimistic tick-off and clear-checked |
| Lifecycle | **real, verified live** | `PATCH /v1/saves/{id}/lifecycle` (`saved → planned → started → completed`) and `GET /v1/saves?lifecycle=…`, which backs the app's Continue rail. Deliberately not a state machine — going backwards is an ordinary thing to want |
| Enrichment — TMDB / Google Places | **built, unverified live** | `enrich/`. Runs *between* classify and embed, because the vector is built from `structured_data` — embedding first would permanently omit the director and the address. Additive only: fills `[unclear]` and missing fields, never overwrites what the content said. 26 tests, all mocked; no real TMDB or Places key has been used |
| RevenueCat / entitlements | **real, verified live** | `billing/` + `V6__billing.sql`. `POST /v1/webhooks/revenuecat`, shared-secret authenticated and **failing closed** when unset. Entitlement derived from expiry rather than event type, with dedupe and out-of-order guards. Six webhook cases driven live — see below |
| Free-tier caps | **real, enforced, off by default** | 20 AI saves/month, 1 Act/week, checked in the worker before the Gemini request and again at the Act's controller for an immediate 402. `weavr.billing.enforce-free-caps` is off until there is a paid tier to escape to; the counters run regardless |
| Spaces — CRUD, roles, invites | **real, verified live** | `space/` + `V7__spaces.sql`. Owner/editor/viewer, revocable invite codes with expiry and use limits, a Space feed. Authorisation verified live including the 404-vs-403 distinction |
| Spaces — comments, votes, activity | **real, verified live** | Votes are a row per (save, user), so the score is a `sum` a replay cannot inflate — the shopping-list lesson applied before it could bite. Activity is deliberately sparse |
| Knowledge types — `recommendation_list`, `checklist`, `itinerary`, `course`, `github_repo` | **real, verified live** | Phase 3 (2026-08-07, [docs/next-phases.md](docs/next-phases.md)). Pure `KnowledgeTypeRegistry` data-change entries — no new Gemini consumer. The live schema pre-flight posted the real 14-branch `anyOf` to the real API and got a flawless `recommendation_list` extraction (0.98 confidence, `reason` populated per item). Bespoke card/detail layout landed only for `recommendation_list`; the other four use the generic fallback |
| Object behaviors — item-state mechanism | **real, verified live-equivalent** | Phase 4 (2026-08-07, [docs/next-phases.md](docs/next-phases.md)). `save_item_states` (`V12__save_item_state.sql`) + `PATCH /v1/saves/{id}/item-state`, the one table/endpoint behind every type's interactivity — full-row upsert, never a merge, same rule as the shopping list. `GET /v1/saves` and `/{id}` batch-embed the caller's states so the detail screen needs no second fetch. Zero AI cost. Backend suite green (329/329, 4 new tests); driven through headless Chrome with `USE_MOCK_DATA` on — a real synthetic click flipped an exercise to "Done" and the progress bar advanced, round-tripping through the mock repository the same way the real endpoint would |
| Object behaviors — checklist / watchlist / workout / course / recipe / place | **real, verified live-equivalent** | Checklist ticks + progress bar, watchlist watched/rating, workout exercise completion + a lenient-parsing rest timer, course section-done (also feeds the Home Continue rail's progress bar), recipe cook mode (`expo-keep-awake`, new dependency) + serving scaling (`scaling.ts`, deterministic local compute), and place/itinerary "Open in Maps" deep links. `buildDetailModel`/`buildCardModel` executed standalone under node against 26 assertions covering all six. Not run on a device — cook mode's screen-wake and the rest timer's live countdown are typechecked and code-reviewed only |
| AI groups (derived tree) | **real, verified live** | `GET /v1/groups` — top level by `knowledge_type`, subdivided by the facet that type already carries (`cuisine`, `genre`, `tags`). A *view* over `saves` rather than a table: no migration, nothing to invalidate, and no Gemini request of its own, because the classify call already produced the signal. `itemCount` is distinct saves in the subtree, not a sum — running it live showed a sum reporting "Watchlist · 2 items" for one film filed under two genres |
| Spaces — duplicate detection | **built, unverified live** | `DuplicateDetector`. A save landing in a shared Space is compared by embedding distance against its neighbours and a *suggestion* is written — never a merge. The 0.15 threshold is a guess |
| `POST /v1/saves` idempotency | **real, verified** | Repeated `Idempotency-Key` header returns the existing save, including under a concurrent-retry race (`V2__save_idempotency.sql`, `SaveServiceTest`). The app's outbox has sent the header since L3 |
| Idempotency on the three creating POSTs | **real, migration probed live** | `POST /v1/spaces`, `POST /v1/spaces/{id}/invites` and `POST /v1/saves/{id}/comments` each mint a row with a server-generated id, so a retried request whose response was lost would create a second one. `V16__idempotency.sql` + `IdempotencyService` store the first attempt's **response** and replay it — not merely a "seen this key" flag, because a retried invite has to come back with the *same code*. `POST /v1/saves` deliberately keeps its own V2 column mechanism; the inconsistency is recorded in both files so it does not get "fixed" |
| Save favorite / archive flags | **real, unit-tested; not run live** | `V10__save_flags.sql`, `PATCH /v1/saves/{id}/flags` (`SaveService.setFlags`). Backs the Library's swipe actions and multi-select bulk actions. 3 new `SaveServiceTest` cases; not yet exercised against a running server |
| Expo app — theme & personalisation | **real, bundles clean** | 78 palette combinations, all audited for WCAG AA. Preferences persist |
| Expo app — auth + save create/list | **real, never run on a device** | Supabase email/password, `POST`/`GET /v1/saves`, all four feed states. Typechecks and bundles; no dev build exists yet |
| Expo app — type-specific save cards | **real, never run on a device** | `SaveCard` renders recipe/movie/place layouts from `knowledgeType` + `structuredData` for `ready` saves; falls back to a flat row otherwise. Typechecks and bundles |
| Expo app — search | **real, never run on a device** | `/search` — **local-first since L5**: the device's own FTS index answers un-debounced (a hit renders inside 180ms, before the server is asked) and `GET /v1/saves/search` merges in behind it, its ranking winning wherever the two overlap. A `semantic`-only hit is badged "related", because a result whose words the user never typed reads as a bug otherwise. A failed server call leaves the local results on screen and says the semantic half is missing, rather than replacing a usable list with an error card |
| Expo app — save detail | **real, never run on a device** | `/save/[id]` — full `structuredData` per knowledge type via `buildDetailModel`, numbered steps, chips, source link. A knowledge type this file has never heard of still renders its fields generically, so adding a type stays a server-side data change |
| Expo app — Library | **real, driven through headless Chrome (mock data); never run on a device** | Redesigned 2026-08-07: per-type icons (`saveTypeMeta.ts`, 4 new glyphs), a tappable horizontally-scrolling "By type" row, bespoke cards for all 9 registry types (was 4), swipe-right-to-favorite / swipe-left-to-archive (`SwipeableRow`), long-press multi-select with bulk actions, working sort (Recently added / A–Z), and `Favorites`/`Archived` filter chips. Long-press → checkbox select → bulk favorite was driven end-to-end via CDP and confirmed working; the swipe gesture itself is typechecked but unverified — synthetic CDP mouse events don't reliably trigger `react-native-gesture-handler`'s `Pan` recognition. **2026-08-12**: an `All / Collections / Types` chip row narrows the type filters to just the entity-bearing or just the plain ones, a full-width search bar replaces the header's search icon (mirroring Home), and the bottom section is "Recent Saves" rather than "Everything" — CDP-confirmed both toggle directions |
| Expo app — Spaces | **real, driven through headless Chrome; never run on a device** | Rebuilt 2026-08-07 into a browse-first workspace — `SpaceCard` (icon identity, avatar stack, last activity, recent-save chips), templated create and a separate join sheet, replacing the old settings-page-style form. **Knowledge-first S0 landed 2026-08-09** ([docs/knowledge-spaces.md](docs/knowledge-spaces.md)): `/space/[id]` is now **Overview / Sources / People / Activity** — Saves is renamed Sources in every piece of copy (app only, no API rename), and Overview leads with counts and the Space's derived collections, computed from the local store by the same merge core the server uses, so it costs no request and no AI. A Space opens on Overview only once its saves actually merge into a collection; otherwise on Sources. Deliberately absent until S1/S2: the merged entity list, per-member watch status, and any done count (`entity_states` is global and per-user, so the only one available would be the viewer's own, misread as the group's). 26 CDP checks plus 29 node-standalone assertions over `spaceOverview.ts`; list → create → join → detail was exercised end-to-end via the CDP recipe in `docs/testing.md`, not just typechecked. **2026-08-12**: `SpaceCard` gained a "Recently added" chip label and a real empty state; Capture can now be opened pre-targeted at a Space (`spaceId` search param, read from three new entry points — a header button and two empty-state CTAs) so a save lands directly in the Space instead of needing `AddToSpaceSheet` afterward — CDP-confirmed with a real note save landing in the Space's own Sources tab. The empty-state branches themselves have no CDP coverage, since no mock Space has zero saves |
| Expo app — lifecycle, discussion, account | **real, never run on a device** | A progress strip and a comment/vote block on `/save/[id]`, a real Continue rail on Home, and a Settings plan card driven by `GET /v1/me` — the invented "2.1 GB of 5 GB" and the fictional "Connected accounts" rows are gone |
| Expo app — weekly digest | **real, deployed live; not yet exercised with real data** | No longer the last sample content — `GET /v1/digest` generates on demand (one Gemini call, cached per user per ISO week) and Home's tile now calls it, hiding itself for `pending`/`empty`. Full backend suite green, `tsc`/`expo export` clean. `V8__digests.sql` confirmed applied on Render (`Migrating schema "public" to version "8 - digests"`) and `generate_digest` confirmed registered in the job runner's startup log — deployed successfully, but the endpoint has not yet been hit against a real week of saves |
| Silent capture — Android | **built, never run on a device** | A config plugin (`app/plugins/withAndroidShareReceiver.js`) adds a no-display `ShareReceiverActivity` + a `ShareUploadWorker` (WorkManager, network-constrained, retried with exponential backoff up to 8 attempts). `expo prebuild -p android` produces the right manifest entry, Gradle dependency and Kotlin sources, verified by inspecting the generated output — no Android SDK on this machine to build or run it |
| Silent capture — iOS | **absent** | The *Open app when saving* toggle exists; the native share extension does not. See [app/README.md](app/README.md) |
| Knowledge collections — entity merge (K0/K1) | **real, verified live** | [docs/knowledge-collections.md](docs/knowledge-collections.md). K0 measured the entity-key normalize spec against the dev database's own real `recommendation_list` saves (2 saves, 7 items, zero overlap — froze the spec, but true-duplicate detection stayed unmeasured for lack of any real duplicate). K1: `collection/` — `Entities.key` (pure), `CollectionService` (merges `recommendation_list`/`itinerary`/`checklist` items into entities; shape 2/3 types untouched), `GET /v1/collections` + `GET /v1/collections/{type}`. `GroupService`'s facet/display-name maps extracted to `common/KnowledgeFacets` so both share one source. No migration, no app change. 24 new tests (365/365 green); live-verified against Supabase with a throwaway user and two seeded saves sharing a "Blue Box" item — the endpoint correctly merged it to `sourceCount: 2`, unioned `genre`, picked the first non-`[unclear]` `year`, and kept each source's own `reason` un-blended, then the throwaway user was deleted |
| Knowledge collections — entity state (K2) | **real, verified live** | `V13__entity_states.sql` + `EntityStateService` + `PATCH /v1/entity-state` (its own controller — `CollectionController`'s own route prefix would have forced the wrong path). `CollectionEntity.state` and `CollectionNode.doneCount` joined in by a second pass over K1's pure output. App-side dual-read (`entityState ?? itemState`, entity wins) for `recommendation_list`'s watch/rating control only. 8 new tests (373/373 green); live-verified with a second throwaway-user round trip beyond K1's read-only one — `PATCH` wrote `{done: true, rating: 5}` for real, and a follow-up `GET` showed it joined into both `GET /v1/collections`'s `doneCount` and the entity payload's `state`, then everything was deleted |
| Knowledge collections — Library UI (K3) | **real, driven through headless Chrome (mock data); never run on a device** | `LibraryScreen`'s new Collections section (one `CollectionCard` per entity-bearing type), `/collection/[type]` (entities sectioned Remaining/Done — the doc's three-way Planning/Watching/Completed split was cut to two-way during implementation, since nothing writes a `state.status` field to populate a third section; **superseded by K6/K7 below** — sections are domain-named now, and K7 does write `state.status`), and an entity detail sheet (`Modal`, not the shared `Sheet` — that component's dismiss assumes a pushed route, and an entity key's `:`/spaces would need path-encoding a route buys nothing for) showing every source's reason unblended with an "Open source" link back to `/save/[id]`. Driven end-to-end via CDP: Library → Collections card → collection screen → entity sheet → "Mark watched" (header count and section both update live) → "Open source" → the *same* item on a different save, never itself touched, already reading Watched — the K2 dual-read proven through the UI, not just asserted |
| Knowledge collections — identity upgrades (K4) | **real; backend live-shaped, TMDB path and new endpoints mock/unit-verified only** | Canonical ids: `tmdbId` written by `TmdbEnricher`/`RecommendationListEnricher`, `Entities.key`'s 3-arg overload preferring `"tmdb:<id>"`. Shape 2 (`movie`/`book`/`place`/`product`/`recipe`/`github_repo`) joins an *existing* shape-1 entity as an extra source only — never its own top-level collection. `V14__collection_overrides.sql` + `CollectionOverrideService` for manual entity merge/rename/collection-rename, threaded through the pure merge core as a defaulted parameter. Pin reuses `entity_states.state.pinned` rather than a fourth override type; it's the one piece with real UI (bookmark toggle, pinned-first sort) — merge/rename have working endpoints and repo methods but no picker UI yet. 20 new backend tests (393/393 green); `merge.ts`/`entities.ts` ported by hand and executed standalone under node. Known gaps, stated not assumed: no `WEAVR_TMDB_API_KEY` here, so the TMDB write path is mock-verified only (same gap `TmdbEnricherTest` already had); the new endpoints were not driven against a live Supabase the way K1/K2 were |
| Knowledge collections — workout compare (K5) | **real, standalone-node verified; never run on a device** | AI synthesis (a merged/rewritten workout program) was evaluated against its own stated bar — a new Gemini budget line before the first call — and didn't clear it, so per the doc's own rule that phase doesn't exist. Shipped instead: `workoutCompare.ts`, pure local compute (zero AI) comparing several workout saves' stated/estimated duration, difficulty, muscle groups and equipment side by side, plus a muscle-group/equipment intersection — never a merge. Reached via a "Compare" multi-select scoped to the workout group screen (`GroupDetailScreen`, reusing `SaveCard`'s existing selection props), landing on a new `/compare-workouts` route. No backend change — every field it reads was already served |
| Knowledge collections — hierarchy (K6) | **real, driven through headless Chrome (mock data); never run on a device** | [docs/knowledge-collections.md](docs/knowledge-collections.md#k6--the-collection-becomes-a-hierarchy-landed-2026-08-10). K1–K5 merged entities correctly and then showed them as one flat list per type, so "Itineraries — 7 places · 1 source" described an extraction rather than a collection. The tree was in fact being built and thrown away: `buildTree` had produced `subgroups` since K1, no screen read them, and `MIN_GROUP_SIZE = 5` folded most away anyway. New `CollectionAxes.java` (mirrored in `app/src/collections/axes.ts`) replaces the single save-level facet with an **axis chain** per type, consumed by one recursive `buildLevel`: depth (Recommendations → Anime → Romance), source (an axis reads the save's field *or* the merged entity's), and cardinality (arrays, so an entity sits in several buckets at one level). `workout` joins shape 1 over `exercises[]`; `mergeNode` resolves a node id by walking the axis path rather than by finding the node in the tree, so a subgroup too small to *show* still resolves to its entities; a type node with zero entities is not built; and "Everything" in the Library is complete again (reversing K3), because a safety net has to be. 15 new backend tests (431/431 green), 69 node-standalone assertions over the real shipped merge core against the real shipped fixtures, 39 CDP checks through all four structural paths |
| Knowledge collections — domain state & surfaces (K7) | **real, driven through headless Chrome (mock data); never run on a device** | [docs/knowledge-collections.md](docs/knowledge-collections.md#k7--domain-state-and-domain-surfaces-landed-2026-08-10). App-only — `entity_states.state` is jsonb written by full replace, so every addition is additive and needed no migration and no server change. Three-state watchlist (Want to watch → Watching → Watched) plus ratings, with the controls **on the row**; `done` stays canonical (each status declares whether it implies done, and writing a status writes both keys), so `doneCount`, the Library's "1 watched" and `SaveDetailScreen`'s dual-read keep working knowing nothing about statuses — and a bare `done: true` with no status reads as the *terminal* status, or previously-watched titles silently un-watch. `entityFields.ts` closes K6's own named gap (a workout exercise's sheet read "No details yet" while `sets`/`reps`/`cues` sat rolled up). Leaf nodes get per-type tabs — a destination's Overview is each trip's route in order, which the merged place list cannot express because a set has no order; Sources is the raw saves. "Start workout" (`/session/[nodeId]`) walks a split's **merged** exercises with rest timers and keep-awake, and its ticks are session-local, never `entity_states`. Backend unchanged (431/431); 122 node-standalone assertions (69 from K6 + 53 new); 39 CDP checks. Two bugs only CDP found: `session/[nodeId]` was never registered in `_layout.tsx` (a route file existing is not a route working), and a click right after dismissing the entity sheet lands on the `Modal` backdrop RNW portals above the document |
| Deleting a save | **real, but untested and missing its tombstone** | `DELETE /v1/saves/{id}` → `SaveService.delete` (ownership check, hard delete, 204); app-side `writeDeleteSave` removes the row locally and queues the op, reached from a third swipe stop past archive and from bulk selection. Unlike favorite and archive it does **not** fire on gesture end — it is the only irreversible action in the app, so both Home and Library confirm first. **Two gaps**: it writes no tombstone, so a save deleted on one device stays cached on the same user's other devices (and on every Space member's, for a shared save) until a pull-to-refresh's `replaceAll` reaps it — `TombstoneService`'s javadoc has the fix sketched; and it shipped with no tests, so neither the ownership check nor the 404-on-foreign-save path is covered. Never run against a live server |
| Action Layer — "what should I do next" nudges | **real, driven through headless Chrome (mock data); never run on a device** | `app/src/collections/nextAction.ts` — one grounded picker per item-bearing type (recommendation, workout, itinerary, checklist), local compute only, no new Gemini call, no synthesis. Surfaced as `NextActionCard` on a leaf `CollectionDetailScreen` and as Home's new "Today" tile (`readTopNextAction`, ranks the best candidate across the whole tree). 29 node-standalone assertions; CDP-confirmed the Home tile, the Romance decide card's "Why?" reasons and hand-off into the entity sheet, and the itinerary card's tab-switch CTA |
| Weekly digest — days-left indicator + dismiss | **real, driven through headless Chrome (mock data); never run on a device** | `digestWeek.ts`'s `daysLeftInWeek` (pure, clamped `[1,7]`) and `digestDismiss.ts` (a dedicated AsyncStorage key, not `Preferences` or the local-first `kv` table) — no content, copy or card-structure change, no extra card height. Dismissal keys off the digest's own `weekStart`, so a new week's digest is never suppressed by last week's dismissal. 7 node-standalone assertions; CDP-confirmed the indicator's real value, dismiss hiding the card, and the dismissal surviving a full page reload (unlike `mockRepository`'s in-memory data) |

**Nothing is faked.** Every "real" row above is genuinely implemented — there are
no mock responses or placeholder implementations in the codebase.

### Verified on 2026-07-30

Against the live Supabase project: `V1` migrated (11.7s) · app started · Flyway
on the session pooler and Hikari on the transaction pooler, confirmed distinct
in the logs · `GET /actuator/health` 200 · unauthenticated and
malformed-token requests rejected 401 · `./mvnw clean verify` green, 67 tests
(2 skipped — the opt-in live yt-dlp pair).

Against a real yt-dlp 2026.07.04 and a live YouTube video: probe returned every
field `SourceMetadata` reads · caption fetch wrote 2 files, not 29 · VTT parsed
to 4,443 characters of prose, from the uploaded track rather than the noisier
auto-generated one · one genuine `HTTP 429` classified as retryable
`source_blocked`. Only YouTube has been exercised — Instagram, TikTok and Reels
are still unproven.

Expo app: `tsc --noEmit` clean · `expo export --platform android` bundles (3.8 MB
Hermes bytecode, every route resolved) · all 78 palette combinations audited for
WCAG AA contrast on body text, muted text, the FAB glyph, the digest label and
both nav-pill states — worst case 4.50:1. **Not run on a device or emulator**:
there is no dev build yet, so nothing here has been seen rendered.

**Phase 1 exit criterion met — the full create path ran against live Supabase.**
Sign in through `/auth/v1/token` (ES256 JWT) → `POST /v1/saves` **202**, save
`6d6a3ce3-…` created → `GET /v1/saves/{id}` **200**, same save → `GET /v1/saves`
**200**, one save listed. That single pass covers the `@CurrentUser` resolver
(`sub` → UUID), the lazy profile upsert on a first-time user, the JSONB mapping,
and a commit through the transaction pooler with `prepareThreshold=0`.

One bug surfaced and was fixed on the way: `NimbusJwtDecoder.withJwkSetUri()`
accepts **RS256 only** by default, and Supabase signs with **ES256**, so every
valid token was being rejected as "no matching key(s) found" (`4299a48`).

**The queue is now verified too, by draining it.** The job runner started against
live Supabase, claimed the row that create path had left behind
(`process_save` for save `6d6a3ce3-…`), ran the handler, wrote its `save_stages`
row and marked the job `succeeded` in 1.5s. That exercises the whole claim
path — `FOR UPDATE SKIP LOCKED`, the `returning` projection, JSONB payload
decoding and the stage upsert — none of which unit tests can reach.

The save itself is still `processing`, and correctly so: the handler is a stub
until the extraction cascade exists.

### Verified on 2026-07-31

**The Gemini classify-and-extract call ran against the real API and produced
real structured saves — watched happen twice, live.** That run surfaced a
decoding bug: Gemini's response carries no charset on its `Content-Type`
header, and reading it with `.body(String.class)` let Spring guess a charset
instead of following the JSON spec's UTF-8 default, so accented text arrived
as mojibake (`café` → `cafÃ©`). Fixed in `GeminiClient.classify` by reading
`.body(byte[].class)` and letting Jackson's byte-based `readTree` decode it
directly — `RestClient` is now built from an injected `RestClient.Builder`
rather than `RestClient.builder()` inline, specifically so a test can bind
`MockRestServiceServer` to it instead of mocking the HTTP layer away. The
regression test for this bug was verified both ways: reproduces the mojibake
against the old `.body(String.class)` code, passes against the fix.

That fix, the primary→fallback model routing and daily-budget guard, and the
response-schema/system-prompt registry now have unit test coverage that did
not exist before today — `KnowledgeTypeRegistryTest` (7), `GeminiBudgetServiceTest`
(6), `GeminiClientTest` (5), `ClassifySaveHandlerTest` (7). `SaveServiceTest`
(6) covers the new `POST /v1/saves` idempotency behaviour below.

**`POST /v1/saves` is now idempotent on a repeated `Idempotency-Key` header.**
`V2__save_idempotency.sql` adds a partial unique index on
`saves (user_id, idempotency_key)`; a retried request returns the existing
save rather than creating a second one, and a race between two concurrent
retries is resolved by catching the constraint violation and re-reading the
winner's row rather than failing the request. This is a different layer from
the job queue's own dedupe, which only stops a duplicate *job* for a save id
that already exists — it never stopped the duplicate save id from being
minted in the first place. No caller sends the header yet.

**The Home feed renders type-specific cards for `ready` saves.** `SaveCard`
(`app/src/components/SaveCard.tsx`) reads `knowledgeType` + `structuredData`
through `buildCardModel` (`app/src/saves/cardModel.ts`) and lays out a
recipe/movie/place-specific card — ingredient or highlight chips, a meta
line, a synopsis — falling back to the existing flat `ListRow` for anything
still processing, `unusable`, or a knowledge type without a bespoke layout
yet. Found in the process: `place`'s title field is named `name`, not
`title`, and `saveTitle()`'s generic fallback was silently missing it — fixed
alongside. Verified the same way as the rest of the app so far: typechecks,
bundles for web. Not run on a device.

**Built, not verified: ASR and the link/PDF branch — the two remaining steps
of the Phase 2 cascade.** Unlike everything above on this date, neither has
touched a real network. `GroqClient` (yt-dlp audio → ffmpeg 16 kHz mono →
Groq Whisper) applies the encoding-byte-fix pattern from the Gemini bug
pre-emptively rather than waiting to hit it again; `LinkExtractor`
(Readability4J) hands Jsoup raw bytes with no assumed charset for the same
reason. An "unsupported URL" from yt-dlp's probe now falls through to link
extraction instead of failing the save outright, and a `.pdf` URL skips
yt-dlp entirely. 46 tests, all mocked (`MockRestServiceServer` for Groq and
the PDF/link downloads, a mocked `ExternalProcess` for ffmpeg) — the same
shape of gap that hid three real yt-dlp defects behind 27 green tests, and
one encoding bug behind 67. Full suite: `./mvnw test` green, 127 tests (2
skipped — the opt-in live yt-dlp pair).

### Also on 2026-07-31 — Android silent capture, and a boot-blocking bug found by actually booting

**The app could not start at all, and no unit test caught it.** `GeminiClient`,
`GroqClient`, `LinkExtractor` and `PdfExtractor` all inject `RestClient.Builder`
on the assumption that Spring Boot auto-configures a prototype-scoped bean for
it — true through Spring Boot 3.x, but Boot 4 split that autoconfiguration out
of `-webmvc` into its own `spring-boot-starter-restclient`, which was never
added to `pom.xml`. Every mocked test binds a `RestClient.Builder` by hand
(`RestClient.builder()`, then `MockRestServiceServer`), so none of them boot a
real `ApplicationContext` and none could have caught a missing autoconfigured
bean — the same shape of gap that hid three yt-dlp defects and a UTF-8 bug
behind mocked-green tests, now on a third layer (DI wiring, not HTTP
behaviour). Fixed by adding the starter; one line.

**With that fixed, the create path was hit directly with `curl` — deliberately
not through the app UI, which has no dev build to run on this machine.**
Admin-created and confirmed a throwaway Supabase user (service-role key),
password-granted a real access token, then:

- `POST /v1/saves` with `Idempotency-Key: <key>` → **202**, `Location` header,
  body `status: "processing"` — the exact response `ShareUploadWorker` (below)
  is written to expect.
- The same request repeated with the **same** `Idempotency-Key` → **202** with
  the **same** save id, and the server log shows `Idempotent replay of save
  key=...` — proving the retry-safety a WorkManager retry depends on, against
  the real unique index, not just `SaveServiceTest`'s mocked race.
- A request with no `Authorization` header → **401**, zero-byte body — the
  status `ShareUploadWorker` treats as non-retryable.
- The job runner claimed the job within seconds and the extraction cascade ran
  for real, failing on the test's fake URL with a retryable `source_unreachable`
  — expected, since `example.com/…` has no video and no yt-dlp extractor. This
  proves the app boots and processes a save end to end; it does **not** newly
  verify ASR or link/PDF extraction against real content, which stays exactly
  as unverified as before this session.

Throwaway user and its stray save were deleted afterward via the admin API.

**Android silent capture is now built, the Android half of "Capture flow:
silent by default."** A local config plugin
(`app/plugins/withAndroidShareReceiver.js`) — not a hand-edited `android/`,
which stays gitignored and regenerable — adds:

- `ShareReceiverActivity`: `Theme.NoDisplay` + `noHistory` +
  `excludeFromRecents`, an intent filter for `ACTION_SEND` / `text/plain`.
  Reads the shared text, enqueues the upload, shows a Toast, finishes.
- `ShareUploadWorker`: a `CoroutineWorker` POSTing to `/v1/saves` with a
  network `Constraints`, exponential backoff, and a per-share idempotency key
  generated once at enqueue time and carried through every retry — the exact
  contract just verified live above.
- `ShareConfigStore` reads a plain JSON file the JS side
  (`app/src/share/nativeShareConfig.ts`) writes on every session or
  `openAppWhenSaving` change. Android needs no App-Group-style bridging for
  this: the share Activity and Worker run in the same process as the JS
  runtime, and `expo-file-system`'s `Paths.document` resolves to the same
  `context.filesDir` a plain `File` read in Kotlin does — confirmed by reading
  both the JS and native module source, not assumed.

Verified: `expo prebuild -p android --clean` produces the activity in
`AndroidManifest.xml`, the `androidx.work:work-runtime-ktx` dependency in
`build.gradle`, and the three Kotlin sources under `.../share/`; a second
prebuild run doesn't duplicate any of them. `tsc --noEmit` is clean. **Not run
on a device or emulator — this machine has no Android SDK.**

### Verified on 2026-08-01 — the visual tier, and two wrong plan decisions

**Phase 4's OCR tier is built, and this time the real binaries were run
*during* development rather than after it.** They overturned two decisions that
were already written into the plan — neither of which a code review could have
caught, because the code faithfully implemented what the plan said.

**The specified filter chain selects zero frames on the content the tier exists
for.** `select='gt(scene,0.25)',mpdecimate` is what CLAUDE.md and the phase plan
both call for. Run against a 12-second video that is one static ingredient card
start to finish, it produces **no frames at all**: frame 0 has no predecessor to
differ from, and nothing afterwards changes. An overlay-only recipe Reel *is* a
static card, so the tier would have silently found nothing on its primary use
case while every mocked test stayed green. Fixed with two extra selector terms —
frame 0 and a periodic sample — and pinned by a live test that runs both chains
side by side (0 frames vs 1, same fixture).

**The recommended preprocessing makes OCR dramatically worse.** "Upscale,
grayscale, CLAHE contrast" assumes a photographic background with a gradient to
flatten. Overlay text is high-contrast and bimodal by design, and ffmpeg's
`histeq` crushes exactly that. Same frame, real tesseract 5.5.0:

| Chain | Words read | Mean confidence |
|---|---|---|
| upscale + greyscale (now the default) | **16 of 16** | **95** |
| the same plus `histeq` | 7 garbled tokens, one line lost | 22 |

`nigatoni`, `(icupiheavy`, `Siclovesiganic`. And 22 is *below* the escalation
floor of 60, so the recommended preprocessing would not merely have degraded the
text — it would have spent a Flash vision request from a 20-per-day pool
repairing damage it had just caused.

**The gate's third signal was confirmed on real content rather than reasoned
about.** Against a real 90-second talking-head video, tesseract returned `|` at
confidence 72, `=` at 91, `—` at 74 — **confident** symbol soup, not something a
confidence threshold can catch. A gate watching per-word confidence alone, which
is the natural reading of "use tesseract's confidence", would pass that to the
model, which would classify the noise into something plausible. The
alphabetic-token-ratio signal rejects it.

Suite: `./mvnw test` green, **184 tests** (5 skipped — the two opt-in live
groups), up from 127. The app was also booted against live Supabase with the new
beans wired in — `Started WeavrApiApplication`, `/actuator/health` 200, an
unauthenticated `POST /v1/saves` 401 — because the last phase shipped a missing
dependency that no unit test could catch, since none of them boot a context.

**What is not verified:** every threshold in the gate is still a guess.
`min-mean-confidence: 60` has been sanity-checked against two extremes and never
measured, and everything the tier has read so far is a fixture it generated
itself. Real text over photographs, motion blur and stylised fonts — where
tesseract fails hard rather than gracefully — are exactly what the thirty-Reel
eval set is for, and it does not exist yet.

### Also on 2026-08-01 — hybrid search, and three things only the live run showed

**Phase 5's search half is in and verified end to end against live Supabase:**
three text saves created over `curl` → classified by Gemini → embedded → found
by `GET /v1/saves/search`. The result worth quoting is *"somewhere nice to eat
in Denmark"*, which returns the Noma save with `match=semantic` — the save says
**Copenhagen** and never says Denmark, so there is no lexical overlap at all.
Full-text alone could not have found it.

Three things were wrong until the live run showed them:

**1. A saved restaurant was unfindable by its own name.** V1's `search_tsv`
generated column covered `raw_caption`, `structured_data->>'title'` and
`->>'summary'`. But `place`'s name field is `name`, not `title` — the same trap
that silently broke `saveTitle()` on the mobile side — and `movie` uses
`synopsis`, not `summary`. Every array field (`ingredients`, `highlights`,
`genre`, `tags`) was invisible too. `V3__search_profile.sql` replaces it with a
weighted vector: **A** = title/name, **B** = caption/summary/synopsis, **C** =
every other value. Measured after: a name match ranks 0.638 against 0.122 for a
long-tail mention.

Two facts confirmed against the live database rather than assumed, because
either one wrong fails the migration and leaves Flyway needing a repair:
`to_tsvector(text)` is only **STABLE** (it reads a session setting) so a
generated column needs the two-arg `to_tsvector('english', …)`; and
`jsonb_path_query_array(…)::text` is immutable and indexes values only, where
the obvious `structured_data::text` also indexes the JSON **keys** — the words
"name" and "ingredients" would have matched every save.

**2. The `[unclear]` sentinel was being indexed.** Reading the generated vector
for a real row showed `'unclear':6C`. It is the registry's marker for genuinely
absent information and appears in most saves, so it was a term nearly every
save shared. `EmbeddingProfile` already drops it on the vector side; `V4`
strips it from the text side for the same reason.

**3. Truncated embeddings are not normalised.** `gemini-embedding-001` is a
Matryoshka model — asking for 1536 dimensions returns a truncated 3072 vector,
with byte-identical leading values. Measured: the full vector has L2 norm
**1.000000**, the truncation **0.691743**. Cosine distance normalises
internally so ranking was already correct, but V1's own DDL comment claims the
column holds "normalised" vectors, and `<->` / `<#>` are not scale-invariant —
a one-character operator change would have silently ranked by magnitude.
`EmbeddingClient` now normalises. Also confirmed live: the API really does
return **3072** dimensions without `outputDimensionality` and 1536 with it.

**And one thing the live run found that no test would have.** A k-nearest
-neighbour search has no concept of "no match" — searching `zzzzqqq` returned
the entire library, ranked, indistinguishable from a real result set. The fix
is a cosine cutoff, and the value was measured rather than remembered:

| Query | Nearest distance | |
|---|---|---|
| "Noma" | 0.266 | hit |
| "somewhere nice to eat in Denmark" | 0.335 | hit |
| "Christopher Nolan" | 0.358 | hit |
| "what should I cook tonight" | 0.366 | hit |
| "zzzzqqq" | 0.425 | miss |
| "how do I renew a passport" | 0.490 | miss |
| "quantum chromodynamics…" | 0.499 | miss |

0.40 sits in the gap, and all three nonsense queries now correctly return
nothing. **The gap is real but the sample is three saves and seven queries** —
provisional in exactly the way the OCR confidence floor is, and a property for
that reason.

Suite: **217 tests** green (5 opt-in live tests skipped), up from 184.

### And the app caught up — search, detail, and a real Library

**Every endpoint the API serves now has a consumer.** Before this, `GET
/v1/saves/{id}` and `GET /v1/saves/search` had none — the backend had run four
phases ahead of anything that could exercise it.

- **`/search`** — debounced at 350ms (each search costs a server-side embedding
  call, so per-keystroke requests would be wasteful as well as slow) and guarded
  by a sequence number, so a slow early response cannot overwrite a fast later
  one. A `semantic`-only hit is badged **related**: a result containing none of
  the words the user typed reads as a bug unless something says why it is there.
- **`/save/[id]`** — the full extraction, not the one-line card. Numbered steps,
  ingredient chips, source link. A save that is still processing or that failed
  is tappable too, and gets a status page that explains itself — `pending` is
  worded as "queued for tomorrow" rather than an error, because it is not one.
- **Library** now reads the same `SavesProvider` as the feed, with real per-type
  counts and filter chips built from the knowledge types actually present. The
  fictional "AI groups" grid is gone rather than left sitting next to real data.

**`buildDetailModel` was executed, not just typechecked.** The app still has no
test runner (a documented gap), but that module imports only a type, so it
compiles standalone and runs under node — driven with the exact
`structuredData` shapes `KnowledgeTypeRegistry`'s few-shot examples produce.
That confirmed `[unclear]` never leaks into the UI, empty arrays render nothing,
`place` takes its title from `name` rather than `title`, an unfinished save
returns `null` so the screen shows a status page, and a knowledge type the
client has never heard of still renders its fields. It also **found a bug**: an
unknown type rendered its title twice, once as the heading and again as a
`Title` field. Fixed.

That is a rung above the usual app-side bar, but it is still not a device.
Nothing here has been seen rendered, and no request has left a phone.

### The one Act — and a bug only a retry would have found

**A saved recipe now becomes a shopping list**, which is the first feature that
spends the structure everything upstream exists to extract. One Gemini call
turns ingredient lines into products, quantities and supermarket aisles; the
result is merged into a single open list per user.

Verified against two real recipes through the live API:

```
PRODUCE            garlic        7 cloves   <- 2 recipes   (3 + 4)
                   lemon         1
PANTRY             olive oil     4 tbsp     <- 2 recipes   (2 + 2)
                   rigatoni      400 g
                   salt          to taste   <- 2 recipes
DAIRY AND EGGS     heavy cream   1 cup
```

Prep instructions are dropped ("3 cloves garlic, minced" → *garlic*),
quantities that are prose survive as prose ("to taste"), and the aisle order is
how a shop is laid out rather than alphabetical — so you walk it once.

**The bug worth recording.** The first implementation *accumulated*: it added
each conversion's quantity to whatever was already on the line. That is correct
exactly once. The job runner re-delivers a job after any transient failure or
stale claim, and on the second delivery garlic went **7 → 10 cloves** and olive
oil **4 → 6 tbsp** — silently, with no error anywhere. Unit tests could not
have caught it, because the accumulation was only wrong across two runs of a
job against a real queue.

The fix is structural rather than a guard: a line no longer stores a total. It
stores **each contributing recipe's own quantity** and recomputes, so the
result depends only on which recipes are on the list, not on how many times
each was converted. Re-running the same conversion now leaves 7 at 7. That fold
is `ShoppingListService.fold`, deliberately static and database-free so
`ShoppingListFoldTest` can pin it.

Running it live also improved the prompt: the first pass produced *"salt and
pepper"* as a single line from one recipe and *"salt"* from another, so they
never merged. The prompt now splits compound ingredients, and salt correctly
shows up once, from both.

Also verified: ticking an item off (204), clear-checked (12 → 11), another
user's item id → **404** rather than a 500 or a silent success, and converting
a non-recipe → **404** before any job is enqueued.

Suite: **243 tests** green, up from 217.

> **Getting a test JWT, correctly.** The throwaway user for this was created by
> inserting into `auth.users` directly — *not* through `/auth/v1/signup`, which
> emails the address you invent. One such bounce earlier today was enough to
> put this project's email-sending privileges at risk. If you do insert
> directly, set `confirmation_token`, `recovery_token`, `email_change_token_new`
> and friends to `''`: GoTrue scans them into non-nullable Go strings, and NULL
> produces a 500 reading `Database error querying schema`, which looks like a
> broken database rather than a malformed row.

### Also on 2026-08-01 — Phase 5's monetisation half, enrichment, and Spaces

Three phases' worth of remaining backend landed together, and **all of it was
driven live against Supabase with two throwaway users and `curl`** rather than
declared done off green unit tests. That found one bug no test could have.

**The bug: every save into a Space failed with a 500, and the `catch` written
to prevent exactly that did nothing.** `SaveService.create` recorded a
`save_added` activity row inside its own transaction — but
`space_activity.save_id` carries a foreign key, and Hibernate has not issued
the `saves` INSERT at that point, because `persist()` with an
application-assigned UUID defers it to flush. The activity insert therefore
violated the constraint. The part worth carrying forward is *why the guard
failed*: **Postgres aborts the entire transaction on any failed statement**, so
the `try/catch` inside `recordActivity` — written on the principle that
metering must never break the thing it describes — was catching an exception
whose damage was already done, and every subsequent statement returned
`current transaction is aborted`. Two fixes, both structural: the `save_added`
row is now written in an **after-commit hook** (an activity entry should
describe something that actually happened, so a rolled-back save should leave
no trace), and `recordActivity` is **`REQUIRES_NEW`**, which is the only thing
that makes its swallow real.

**The RevenueCat webhook derives entitlement from expiry, not from event type**,
and six cases were driven live to prove it:

| Sent | Result |
|---|---|
| `INITIAL_PURCHASE`, unexpired | `applied/active` — `/v1/me` flips to `pro`, limits to unlimited |
| the same event id again | `ignored/duplicate` |
| `$RCAnonymousID:…` purchaser | `ignored/unknown_user`, **200** not 4xx |
| `CANCELLATION` before expiry | still `pro` — cancelled means auto-renew off, **not** access ended |
| an older `EXPIRATION` arriving late | `ignored/out_of_order`, still `pro` |
| a current `EXPIRATION` | `applied/inactive`, limits back to 20/1 |

The type-switch implementation every tutorial shows gets rows 4 and 5 wrong:
it would cut off a user who has paid through to the end of the period, and let
a delayed retry hand a lapsed one a permanent subscription. Nothing here
switches on the event type, so a type invented in 2027 still resolves.

**Spaces closed a hole that had been open since Phase 1.** `POST /v1/saves`
took `spaceId` straight from the request body with nothing checking it — any
authenticated user could drop a save into any Space whose id they had once been
shown. Verified fixed live, along with the rest of the authorisation model: a
non-member gets **404** (the existence of a Space they were never invited to is
not theirs to learn), a member with too low a role gets **403** (they already
know it exists, so hiding it would only confuse them), and a single-use invite
returns 404 on its second use.

**Votes were built as rows, not a tally, because the shopping list already
taught that lesson.** A stored score is correct exactly once and drifts on any
replay — the same shape as the accumulate-on-add bug that silently took garlic
from 7 cloves to 10. `save_votes` has a composite PK and the score is a `sum`,
so re-sending the same vote is a no-op: verified live at +1, +1 again (still 1),
a second person (2), cleared (1).

**One thing the live run improved rather than fixed:** re-sending an identical
vote was writing a second activity row, so a single user action produced three
lines in a feed documented as "meaningful events only". Now a vote only reaches
the feed when it actually changed.

**Enrichment guards against a failure the search half already hit in another
costume.** TMDB answers a garbled title with *something* and Google Places
answers "Noma" with a coffee roaster — neither has a concept of "no match", the
same way a k-NN query did not until a distance cutoff was added. `TitleMatch`
is the cutoff here, and enrichment is additive-only, so the worst a wrong match
can do is add a field rather than overwrite what the user's own content said.

Suite: **294 tests** green (5 opt-in live tests skipped), up from 243. `tsc
--noEmit` clean and `expo export --platform android` bundles at 4.8 MB. Two
migrations (`V6__billing.sql`, `V7__spaces.sql`) applied cleanly to live
Supabase in 6.8s.

**What is not verified:** enrichment has never used a real TMDB or Places key,
and duplicate detection has never compared two real saves — both are mocked-test
green, which is precisely the state that hid three yt-dlp defects and a UTF-8
bug. The Act cap was unit-tested but not driven live, since exercising it needs
a real recipe through the Gemini pipeline.

### Also on 2026-08-02 through 2026-08-05 — Render's YouTube bot check, and the RapidAPI path that actually cleared it

**The Render deployment hit a failure `YtDlpLiveTest` had never exercised: YouTube's "Sign in to confirm you're not a bot" on every probe.** The live test runs from a residential dev machine's IP; Render's is a datacenter IP, which YouTube challenges far more aggressively. Two mitigations were tried against the real deploy, and **both are now confirmed failed**, not just untested:

- `WEAVR_YTDLP_COOKIES_BASE64` decodes to a `--cookies` file at startup — present and correct in the probe command, and the job still failed. A cookie exported on a residential machine and replayed from an unrelated cloud IP is itself a bot signal, and the `web` player client's challenge separately wants a PO token cookies don't supply.
- `--extractor-args youtube:player_client=tv,android,web` (`WEAVR_YTDLP_PLAYER_CLIENTS`) was tested against **8 fresh real YouTube URLs** (4 Shorts, 4 regular) with both cookies and the override confirmed correct in the command line — all 8 hit the identical bot-check error. That result ruled out a config bug: Render's Oregon IP looks blocked by YouTube independent of auth method.

**A live-cookies file was accidentally committed while debugging this** (`www.youtube.com_cookies.txt`) and had to be scrubbed from tracking; `*cookies.txt` is now `.gitignore`d. Worth repeating for the next debugging session that writes a credential to disk: the gitignore entry belongs in the same commit as the code that writes the file, not a follow-up commit after it's already leaked once.

**`RapidYtClient` (2026-08-05) sidesteps the bot check instead of continuing to negotiate with it, and it is confirmed live.** A two-call RapidAPI path (`ytstream-download-youtube-videos`) fetches metadata and a caption-track XML without yt-dlp ever contacting YouTube directly; `ExtractionCascade` tries it first for any URL it recognises as YouTube, falling through to the unchanged yt-dlp path on empty result or failure. Gated by `WEAVR_RAPID_YT_API_KEY` — blank disables it, same opt-in shape as the Groq and enrichment keys. **Render's own logs confirm it working**: two real YouTube URLs (a Shorts link and a `youtu.be` link) each logged `RapidAPI probe succeeded` and completed all four pipeline jobs — `process_save`, `classify_save`, `enrich_save`, `embed_save` — at 22:42 and 22:43 UTC on 2026-08-04. That was also the first deploy to actually contain the feature: the commit that added `RapidYtClient` failed Render's build three times first, because Maven runs the test suite as part of the Docker build and a follow-up commit was needed to update `ExtractionCascadeTest`'s constructor — a red build blocks the deploy itself here, not just CI.

**The same verification pass surfaced an unrelated, real problem: the DB pool is too small for concurrent load.** One request in that window hit `weavr-pool - Connection is not available, request timed out after 12502ms (total=3, active=3, idle=0, waiting=1)` and came back as an unhandled exception. `WEAVR_DB_POOL_MAX=3` was sized for Supabase's free-tier connection scarcity, and it saturated under ordinary manual test traffic — a fourth concurrent request waited 12.5 seconds and then failed rather than queuing successfully. Not yet reproduced deliberately or measured against real concurrent-save load; noted here because checking one thing found another, same shape as three yt-dlp defects and a UTF-8 bug before it.

Also checked while verifying this: `cpu-limit` is 0.15 vCPU and `memory-limit` is ~512 MB (Render free tier), and idle memory sits around 355–373 MB — roughly 70% of the limit before a single job runs. `WEAVR_JOBS_CONCURRENCY=1` bounds how bad a concurrent-job spike can get, but nothing has measured what OCR/ffmpeg actually costs on top of that baseline on the real instance.

### Also on 2026-08-12 — an Action Layer, and a real end-to-end CDP proof rather than a screenshot

**A user-proposed "coaching" redesign for Recommendations turned into a general cross-type engine, scoped to local compute only.** The proposal itself named the two AI-cost failure modes to avoid: a generated "you should watch this because…" explanation, and a synthesized itinerary/routine merged across sources — the latter is exactly the shape-3 synthesis `docs/knowledge-collections.md`'s K5 already evaluated against its own stated bar (a new Gemini budget line) and declined. So `nextAction.ts` is pure arithmetic and sorting over fields the merge core already produced: `recommendation_list` sorts open (not-done) entities by watching-status, then source count, then recency; `workout` reuses `workoutLoad.ts`'s existing `estimateSessionLoad`; `itinerary` states a place count and source count only, asserted by name never to invent a day range or route; `checklist` requires a list to be genuinely partway done. Each has its own minimum-candidates threshold, so most libraries clear no type's threshold most of the time — an absent card, not an empty one.

**Verified with a real CDP run, not a screenshot.** `USE_MOCK_DATA` flipped locally (restored after, never committed — the pre-commit hook would force it back regardless), `expo start --web` on a spare port, headless Chrome on `--remote-debugging-port`. The Home "Today" tile rendered a real workout candidate ("Start Push day · 16 sets across 5 exercises · ~32 min (est.)"), clicking "Why Start Push day?" expanded to "5 exercises saved under Push" / "From 2 saved workouts", and clicking "Start workout" navigated to `/session/workout~push` with the real exercise list. On the Recommendations → Anime → Romance leaf, the decide card picked "Blue Box" (2 sources, the highest in the group) with four grounded reasons, and its "Watch this" button opened the real entity detail sheet — "MENTIONED IN 2 SAVES" with each source's own unblended reason. The itinerary leaf for Japan showed "Plan your time in Japan · 6 places saved here, from 2 of your itineraries", and its "View places" button switched the tab from Overview to the merged Places list, exactly as coded.

Verified: 29 node-standalone assertions over the real shipped `nextAction.ts`, `tsc --noEmit` clean, `expo export --platform web` clean, and the CDP run above. **Not run on a device.**

**The same day, the Weekly digest card gained a "days left" indicator and a dismiss control, without touching its content, copy or structure.** Both key off `DigestResponse.weekStart` — the server's own Monday-UTC boundary — rather than a second client-computed week boundary. The indicator (`daysLeftInWeek`, clamped to `[1, 7]`) and the dismiss flag (its own AsyncStorage key, keyed to `weekStart` so a new week's digest is never suppressed by last week's dismissal) both live in one header row alongside the existing "Weekly digest" label, so the card's height is unchanged. Verified: 7 node-standalone assertions (week-start day, mid-week, last day, a stale elapsed week clamped to 1, a future `weekStart` from clock skew clamped to 7, a malformed string, and same-UTC-day consistency across a local time-zone offset), `tsc --noEmit` and `expo export --platform web` clean, and a CDP run against mock data: "5 days left" on the real current week-day, dismiss hiding the card, and the dismissal surviving a full page reload — unlike `mockRepository`'s in-memory saves, which reset on reload, confirming the write actually persisted rather than only updating in-memory state. **Not run on a device.**

## Running the app

```bash
cd app && npm install
npx expo run:android          # or run:ios
npx expo export --platform android   # bundles without a device
```

Expo Go will not work once `expo-share-extension` and `react-native-purchases`
land — an EAS dev client is required. See [app/README.md](app/README.md).

## Running the API

Requires JDK 25. Maven comes from the wrapper — nothing to install.

```bash
cp .env.example .env          # two DB passwords + SUPABASE_ANON_KEY
set -a && source .env && set +a
cd api && ./mvnw spring-boot:run
```

Tests need no database:

```bash
cd api && ./mvnw test
```

### Verifying the migration

Flyway runs on startup. First boot against a fresh Supabase project creates
every table in `V1__init.sql`. If it fails, check the two URLs before anything
else — see below.

### Smoke test

This is the path verified on 2026-07-30. Mint a token against Supabase Auth
directly — `SUPABASE_ANON_KEY` is the public anon key, safe to use here:

```bash
export SUPABASE_ACCESS_TOKEN=$(curl -s \
  "$WEAVR_SUPABASE_URL/auth/v1/token?grant_type=password" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…"}' | jq -r .access_token)

curl -X POST http://localhost:8080/v1/saves \
  -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"url","sourceUrl":"https://www.youtube.com/shorts/abc123"}'
```

A brand-new user needs its email confirmed before password grant works — do that
with the admin API (service-role key) rather than clicking a link in an inbox.

Expect `202` with a body containing `"status":"processing"` and a `Location`
header. A row appears in `saves`, and a matching row in `jobs` — which the job
runner claims within a couple of seconds, logging `Job … succeeded`. The save
stays `processing`, because the handler behind that job is still a stub.

## Things that will bite you

**Two database URLs, and they are not interchangeable.**
`WEAVR_DB_URL` → transaction pooler (**6543**), `prepareThreshold=0`, small pool.
`WEAVR_FLYWAY_URL` → session pooler (**5432**). Flyway takes a session-level
advisory lock and runs DDL in transactions; both break under the transaction
pooler — and they break *later*, under concurrency, not on the first migration.

**Spring Boot 4 is not Spring Boot 3.** Two differences that make almost every
tutorial you'll find wrong:
- Jackson 3: `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, not
  `com.fasterxml.jackson.databind`. Annotations (`@JsonValue`, `@JsonCreator`)
  stayed on `com.fasterxml.jackson.annotation`.
- Starters were renamed: `spring-boot-starter-webmvc` (not `-web`),
  `spring-boot-starter-security-oauth2-resource-server` (not
  `spring-boot-starter-oauth2-resource-server`), plus a `-test` companion per
  starter.

**`NimbusJwtDecoder` accepts RS256 only until you tell it otherwise.** Supabase
signs access tokens with **ES256** (EC P-256), so a stock
`NimbusJwtDecoder.withJwkSetUri(...).build()` rejects every valid token with a
misleading "no matching key(s) found" — it reads like a JWKS or issuer problem,
and it is neither. Hence the explicit `.jwsAlgorithm(SignatureAlgorithm.ES256)`
in `SecurityConfig`.

Note that `jwsAlgorithm()` *adds to a set* and the RS256 default applies only
while that set is empty, so the accepted set is now exactly `{ES256}`. Supabase's
asymmetric signing keys can be ECC P-256 **or** RSA 2048 — if that key is ever
rotated to RSA, auth breaks completely with the same confusing error. Add
`.jwsAlgorithm(SignatureAlgorithm.RS256)` alongside it before rotating anything.

**RLS is not the API's access-control boundary.** Spring connects as `postgres`,
which carries `BYPASSRLS`, so the policies in V1 do not constrain it.
Authorization lives in the service layer, keyed off the JWT `sub` claim. The
policies exist for paths where a client talks to Postgres directly. If API reads
ever start returning zero rows unexpectedly, check that the connecting role
still has `BYPASSRLS` before looking anywhere else.

**The embedding dimension is a one-way door.** `vector(1536)` is baked into V1,
sized for `gemini-embedding-001` with `outputDimensionality: 1536` requested
**explicitly** — the model defaults to 3072. Changing it later is a migration
*plus* a full re-embedding backfill.

**pgvector has no Hibernate type.** `saves.embedding` is deliberately not mapped
on the `Save` entity. It is read and written through `JdbcClient` by the
embedding and search code, which also owns the similarity queries. Add the
`com.pgvector:pgvector` dependency when Phase 3 needs it.

**Supabase projects pause after ~7 days of inactivity.** A paused project during
judging is a demo-day failure. The keep-warm job must issue a real query, not an
HTTP ping to a static endpoint.

## Known gaps

**Render's YouTube bot check — fixed and confirmed, via RapidAPI, not via yt-dlp** — see the dated section above.

- yt-dlp's own YouTube path stays blocked on Render's datacenter IP (confirmed: cookies failed, the player-client override failed, 8/8 real videos in the second case). `RapidYtClient` bypasses YouTube directly and is confirmed working live — two real saves completed the full pipeline on 2026-08-04. If `WEAVR_RAPID_YT_API_KEY` is ever unset, the app falls straight back to the confirmed-broken yt-dlp path with no warning — the key exists in `render.yaml` but `sync: false` means it is not set automatically, so an operator has to add it manually in the Render dashboard, and nothing currently alerts if it goes missing or the RapidAPI quota runs out.
- **Fixed 2026-08-05: `WEAVR_DB_POOL_MAX` was 3, saturated under ordinary manual test traffic** — one request timed out after 12.5s waiting for a connection during the RapidAPI verification pass. Bumped to 5 in `render.yaml`, inside this doc's own ~5–10 guidance for Supabase free tier. Not load-tested against real concurrent-save volume, just against the manual burst that found it.
- **Fixed 2026-08-05: the Gemini fallback model (`gemini-2.5-flash`) was 404ing on every call** — deprecated for this project's key ("no longer available to new users"), confirmed by reproducing the exact call directly. It's the one fallback both the confidence-retry backstop and the OCR vision-escalation tier share, and both were failing closed with no visible symptom — likely broken since the primary model moved to the 3.x line. Now `gemini-3.6-flash`, verified against the real key before landing. Found while checking the 7-Reel Instagram batch, not by looking for it.
- **Idle memory on the free-tier instance sits around 355–373 MB against a 512 MB limit** (~70%) before any job runs. `WEAVR_JOBS_CONCURRENCY=1` bounds concurrent-job risk, but no measurement exists yet of what a real OCR/ffmpeg job adds on top on the actual Render instance, as opposed to a dev machine.

**Verified against a real yt-dlp** (closed — see [testing.md](docs/testing.md#what-running-the-real-binary-found))

- The probe and caption paths now run end to end against yt-dlp 2026.07.04 and a
  real YouTube video, via the opt-in `YtDlpLiveTest`. Doing so found three
  defects that no amount of mocking would have: a `--sub-langs` regex that
  expanded into 29 downloads and earned an HTTP 429, a non-zero exit discarding
  captions already written to disk, and caption selection ranked by file size —
  which reliably preferred the bloated auto-generated track over the uploaded
  one. All three are fixed and pinned by tests.
- **The container must still install both binaries.** A plain JRE base image has
  neither, and the failure mode is every save retrying until it exhausts
  `max_attempts`. Use the distro package for ffmpeg; the standalone Windows
  build used locally is ~94 MB per binary because it is statically linked.
- **ASR (and the link/PDF branch) are built but unverified live** — the
  yt-dlp caption path's own history is the reason to say this plainly rather
  than call it done: 27 mocked-green tests still missed three real defects,
  and the classify path's 67 missed a UTF-8 encoding bug, both only found by
  running the real thing. ASR, `LinkExtractor` and `PdfExtractor` have 46
  tests between them, all mocked. A post with neither captions nor a
  description now falls through to Whisper instead of failing outright — in
  theory; nothing has confirmed it does in practice yet.

**Blocking the Phase 1 exit**

- **The app ran on an Android device for the first time on 2026-08-01, and most
  of it is still unproven.** What that run did prove: sign-in works, Home
  renders, and the feed fetches real saves from the live API over the network.
  What it immediately disproved, twice: the bottom nav painted nothing —
  neither the floating pill nor the capture FAB — leaving no way to reach
  Library, Spaces or Capture; and once the nav worked, the Capture sheet's
  tiles painted over the footer with their rows collapsed to no height. The nav
  is fixed and confirmed on the phone. The Capture sheet's fix — `flex: 1` on
  an auto-height column child is zero height in Yoga but not in CSS — is
  **unverified**, because Chrome renders that screen correctly either way and
  so cannot confirm it. The headless-Chrome recipe, and the boundary it just
  ran into, are in
  [docs/testing.md](docs/testing.md#looking-at-a-screen-without-a-device).
  Everything past Home's feed, the nav and the Capture sheet — Library, Spaces,
  save detail, search, the morph — remains written but unwatched, and no link
  has yet been posted from a phone.

**Correctness**

- **No integration tests against a real database.** Unit tests cover validation
  and enum mapping only. Testcontainers with a `pgvector/pgvector` image is the
  natural next step — and the transaction-abort bug above is the argument for
  it: no amount of mocking can reproduce "Postgres poisoned the transaction, so
  your catch block was decorative".
- **The `Idempotency-Key` race recovery is probably broken, and always has
  been.** `SaveService.create` catches `DataIntegrityViolationException` and
  re-reads the winner's row — but that read runs inside the same transaction
  the violation just aborted, so it would fail with `current transaction is
  aborted` rather than returning the existing save. It has never been observed,
  because the pre-check catches every non-concurrent replay and the live
  idempotency test exercised that path, not this one. The recovery needs its
  own transaction. Found while fixing the activity-feed bug above; not fixed,
  because it deserves a test that actually races two requests.
- **Enrichment and duplicate detection are mocked-test green only.** No real
  TMDB or Google Places key has been used, and no two real saves have been
  compared. This is the same shape of gap that hid three yt-dlp defects behind
  27 passing tests and a UTF-8 bug behind 67.
- ~~**`SecurityConfig` accepts `{ES256}` and nothing else.**~~ **Closed 2026-08-02.**
  `jwsAlgorithm()` adds to a set rather than extending the defaults, so naming
  only ES256 left a Supabase key rotation to RSA able to take auth down
  entirely, reporting the same misleading "no matching key(s) found" the call
  exists to prevent. Both algorithms are now listed.
- **Android's silent-share upload can be holding a stale access token.**
  `nativeShareConfig.ts` mirrors the token on session change, but a queued
  `ShareUploadWorker` retry can fire after the token has expired while the app
  was backgrounded — and `SessionProvider` deliberately stops the refresh timer
  while backgrounded, so nothing refreshes it in the meantime. The worker
  already treats a 401 as non-retryable rather than looping forever, so the
  failure mode is a silently dropped share, not a battery drain — but the user
  already saw "Saved to Weavr" before the network call ran. Needs either a
  refresh-token exchange inside the worker or a shorter optimistic-Toast
  window; not decided yet.

**Deferred by design, but easy to mistake for bugs**

- **A save reaches `ready` (or `failed`) server-side now, but the app only
  finds out on pull-to-refresh.** `process_save` runs the extraction cascade
  and `classify_save` runs Gemini, so a save genuinely progresses. The app
  still deliberately does not poll — it would spin waiting for a transition
  the push notification is meant to signal instead — so a freshly created save
  keeps its "Processing" pill until the user pulls to refresh or the
  notification lands. Intentional.
- **Auth is email/password, not anonymous.** §10 of the plan called for anonymous
  auth in Phase 1. Password sign-in was chosen instead because it is the path
  already proven end-to-end, and anonymous sign-in needs a dashboard toggle that
  has not been enabled. Consequence: a new account needs its email confirmed via
  the admin API before it can sign in, which is friction during testing.
- **The session is not in a shared Keychain yet.** It lives in AsyncStorage,
  which the iOS share extension cannot read. Moving it — and storing the
  *refresh* token, not just the access token — is a prerequisite for the
  extension, and the app README flags it as painful to retrofit.
- **Capture tiles other than Paste Link do nothing.** They are visibly disabled
  rather than silently inert, and each needs its own capture surface.
