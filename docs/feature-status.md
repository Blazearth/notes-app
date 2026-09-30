# Feature status

**As of 2026-08-10, the Shipaton build window (Aug 1 – Sep 30).**

## The bar

A feature is **done** only if it is implemented *and* verified against the real
thing — a live API, a real binary, a real device. Code that compiles, passes
mocked tests and has never met reality is **not done**, and this document is
deliberately harsh about that distinction.

That is not pedantry. This project has three separate incidents proving the
distinction is load-bearing:

- The extraction cascade had **27 passing tests and three real defects**. All
  three were invisible to a mocked process.
- The classify path had **67 passing tests** and shipped a UTF-8 bug that
  turned `café` into `cafÃ©`.
- The app **typechecked, bundled, and rendered in a browser** — then opened on a
  phone with no navigation bar at all.

Each gap was closed only by running the real thing. So "it's written" is
recorded here as *not done*.

| Status | Meaning |
|---|---|
| ✅ **Done** | Implemented and verified against the real thing |
| 🟠 **Unverified** | Code complete; only mocked tests, or never run for real |
| 🔵 **Mock-only** | Works in the app, but no backend serves it — *none left as of 2026-08-02* |
| ⛔ **Not built** | Does not exist |

---

## Capture — how content gets in

| Feature | Status | What's missing |
|---|---|---|
| Share to app — iOS (silent) | ⛔ Not built | The whole native share extension. **This is the product's core promise.** Blocked on moving the session to a shared Keychain with the *refresh* token — it currently lives in AsyncStorage, which the extension cannot read |
| Share to app — Android (silent) | 🟠 Unverified | `ShareReceiverActivity` + `ShareUploadWorker` exist as a config plugin, and `expo prebuild` generates them correctly. Never run on a device or emulator — no Android SDK on the dev machine |
| Paste Link (in-app) | 🟠 Unverified | Posts to `POST /v1/saves`. Works against the mock; **no link has ever been posted from a phone** |
| Screenshot / photo capture | ⛔ Not built | Tile is visibly disabled. Needs Tier 0 on-device OCR (Apple Vision / ML Kit) |
| Camera capture | ⛔ Not built | Tile disabled |
| Voice memo | ⛔ Not built | Tile disabled |
| Scan document | ⛔ Not built | Tile disabled |
| Upload file | ⛔ Not built | Tile disabled |
| Text note | ⛔ Not built | Tile disabled. The API accepts `sourceType: 'text'`; no UI reaches it |
| Import | ⛔ Not built | Tile disabled |
| Idempotent create | ✅ Done | Verified live, including a concurrent-retry race. No caller sends the header yet — it exists for the unbuilt iOS extension |

**Seven of the eight capture tiles do nothing.** `IMPLEMENTED` in `CaptureSheet.tsx` holds exactly one id — `link` — and the other seven render visibly disabled rather than silently inert.

---

## Extraction — getting text out of a source

Platform coverage is the gap most likely to be mistaken for done. The cascade is
verified; it is verified **against YouTube**.

| Feature | Status | What's missing |
|---|---|---|
| YouTube — captions + metadata | ✅ Done, via RapidAPI not yt-dlp | yt-dlp's own path is proven from a residential IP only — Render's datacenter IP hits YouTube's bot check on every probe, and both cookies and a player-client override were tried live and **confirmed to fail** (8/8 real videos). `RapidYtClient` bypasses YouTube directly instead and **is** confirmed live: two real YouTube URLs completed the full pipeline (process → classify → enrich → embed) on Render on 2026-08-04 |
| **Instagram Reels** | ✅ Done | **7 of 7 real public Reels** run through the live cascade on Render on 2026-08-05 — one solo, then 6 back-to-back roughly 5-6s apart to check for rate-limiting. All 7 cleared yt-dlp's plain probe directly, no auth wall, no retry, no slowdown across the burst; extracted 117–19,254 chars depending on how much caption text each post actually had, classified and embedded every time. Still unmeasured: private accounts, and whether volume far beyond 6-in-a-row changes anything — but "increasingly requires authentication" was the planning assumption, and Render's IP has not hit it once across 7 tries |
| **TikTok** | 🟠 Unverified | Same cascade. **Still never exercised end-to-end** — a real run was attempted 2026-10-01 but the dev machine is in India, where TikTok is banned at the network level (`www.tiktok.com` resolves to an ISP sinkhole, TCP connect times out; Instagram as a control connects in 25ms). The attempt did prove the failure path: classified `TIMEOUT` → retryable `source_unreachable`. The real test has to come from Render's US IP — set `WEAVR_CANARY_ENABLED=true` and grep `extraction_attempt context=canary platform=tiktok` |
| YouTube — Data API v3 fallback | 🟠 Built, never called for real | RapidAPI → **YouTube Data API** → yt-dlp (2026-10-01). Metadata only (title/description/channel/duration/thumbnail) — it cannot fetch captions for others' videos and never claims to. 16 mocked tests + 6 cascade-fallback tests. **No `WEAVR_YOUTUBE_API_KEY` exists yet**, so it has never made a real request and is inert in production until one is set |
| Extraction health (attempt records + canary) | ✅ Built, 🟠 canary off | Every provider call logs `extraction_attempt platform= provider= category= latency_ms=…` (categories: BOT_CHECK, HTTP_429, TIMEOUT, …). Canary probes 3 long-lived URLs every 12h — live-run from a residential IP only; `WEAVR_CANARY_ENABLED` is `false` on Render |
| ASR fallback (Groq Whisper) | 🟠 Unverified | 9 tests, all mocked. **No real Groq call has ever been made** |
| Plain links (Readability4J) | 🟠 Unverified | 7 tests, all mocked. No real page fetched |
| PDFs (PDFBox) | 🟠 Unverified | Included in the above. No real PDF fetched |
| Keyframes + local OCR | ✅ Done¹ | Verified against real ffmpeg 8.0 and tesseract 5.5.0 |
| OCR escalation thresholds | ⛔ Not built | **The 30-Reel eval set does not exist, so every threshold is a guess.** Both failure directions are silent: too strict burns the scarce Flash pool, too loose ships invented ingredients |
| Flash vision escalation | 🟠 Unverified | Request shape pinned by a mock. No real vision call made |

¹ The *toolchain* is proven end to end, but only against fixtures the test
renders itself. It has never read a real Reel — real text over photographs,
motion blur and stylised fonts are exactly where tesseract fails hard.

---

## Understanding — turning text into structure

| Feature | Status | What's missing |
|---|---|---|
| Classify + extract (one Gemini call) | ✅ Done | Ran against the real API and produced real structured saves |
| Model routing + budget guard | ✅ Done | Primary → fallback, daily counter persisted. The fallback model itself was silently 404ing in production until 2026-08-05 (deprecated `gemini-2.5-flash`) — the routing logic was never the bug, the pinned model name was |
| Knowledge-type registry | ✅ Done | Adding a type is a data change |
| Enrichment (TMDB / Google Places) | 🟠 Unverified | 26 tests, all mocked. **No real TMDB or Places key has ever been used** |
| Embeddings | ✅ Done | 1536-d, normalised client-side |
| Real Gemini RPD | ✅ Done | Confirmed live in AI Studio 2026-08-05: primary (Flash-Lite) really is 500 RPD, the feared reduction to 250 did not happen. But the fallback/vision tier (Flash) is only **20 RPD**, not the ~250 CLAUDE.md had assumed — that pool was never going to add meaningful save capacity, and any future consumer of it (digest, Act conversion) needs to be budgeted against 20/day, not 250 |

---

## Finding

| Feature | Status | What's missing |
|---|---|---|
| Hybrid search (FTS + vector, RRF) | ✅ Done | Verified live: *"somewhere nice to eat in Denmark"* returns a save that says Copenhagen and never Denmark |
| Search screen | 🟠 Unverified | Debounced, race-guarded, all five states. Never used on a device |
| Library screen | 🟠 Unverified | Real per-type counts and filter chips. Never used on a device |
| Similarity cutoff (0.40) | 🟠 Unverified | Measured on **three saves and seven queries**. Provisional |

---

## Organising

| Feature | Status | What's missing |
|---|---|---|
| **AI groups** | ✅ Done | `GET /v1/groups` derives the tree per request from each save's `knowledgeType` and the facets the classify call already extracted — no table, no migration, and no Gemini request of its own. Verified against live Supabase with seeded saves |
| **Subgroups / nesting** | ✅ Done¹ | Recursive server-side and client-side. Verified live: recipes split by `cuisine`, movies by `genre`. ¹ The screens have still never run on a device |
| Group detail screen | 🟠 Unverified | Now backed by a real endpoint; verified in Chrome against mock data only |
| Spaces — CRUD, roles, invites | ✅ Done | Authorisation verified live, including 404-vs-403 |
| Spaces — comments, votes, activity | ✅ Done | Verified live |
| Spaces — duplicate detection | 🟠 Unverified | The 0.15 threshold is a guess; no two real saves compared |
| **Knowledge collections** — entity merge (K1–K2) | ✅ Done | [docs/knowledge-collections.md](knowledge-collections.md). `collection/` merges item-bearing types' items into cross-source entities; `entity_states` holds per-(user, entity) state. Verified live against Supabase twice with throwaway users — a "Blue Box" shared across two saves merged to `sourceCount: 2` with `genre` unioned and each source's `reason` un-blended, and a `PATCH /v1/entity-state` round trip showed `doneCount` follow |
| Knowledge collections — hierarchy (K6) | 🟠 Unverified | The axis chain: a type subdivides by a *chain* of axes, each reading the save's field or the merged entity's, each possibly multi-valued — Itineraries → Japan → Tokyo, Recommendations → Anime → Romance, Workouts → Push → Bench press. `workout` joined the item-bearing types. 15 new backend tests (431/431), 69 node-standalone assertions, 39 CDP checks against mock data; **never run on a device**, and the tree has not been exercised against a real multi-save library on the server |
| Knowledge collections — domain state & actions (K7) | 🟠 Unverified | Three-state watchlist + ratings on the row, per-type detail fields, per-type leaf tabs (a destination's Overview is each trip's route in order), and a workout session over the merged exercises. App-only — `entity_states.state` is jsonb written by full replace, so no migration. 53 further node-standalone assertions, 39 CDP checks; **never run on a device**, so haptics, `useKeepAwake` and the countdown are unproven |
| Knowledge collections — manual merge/rename UI (K4) | ⛔ Not built | The endpoints and the merge-core threading exist and are tested; only pin has UI. Nothing yet gives a user a duplicate to point at, and the measured-first rule still forbids auto-merge |
| Spaces — knowledge-first IA (S0) | 🟠 Unverified | [docs/knowledge-spaces.md](knowledge-spaces.md). Saves→Sources rename, an Overview tab leading the strip, and default-tab-by-whether-the-Space-merges. App-only: the collections are derived from the local store by the same merge core the server runs, so S0 needed no endpoint. Driven through headless Chrome against mock data (26 checks) plus 29 node-standalone assertions; never run on a device |
| Spaces — space-scoped collections (S1) | ⛔ Not built | `CollectionService` needs a scope parameter and two `/v1/spaces/{id}/collections` endpoints. The client already derives this locally; what only the server can add is `addedBy` — a save's owner is not on `SaveResponse` |
| Spaces — shared progress + merged list (S2) | ⛔ Not built | The batched all-members `entity_states` read, per-member rollups, and the discussion block. Blocks the Overview from showing a watchlist or any done count — with only the viewer's own global state, "1 watched" reads as a claim about the group. Carries an undecided disclosure question (see the doc) |
| Spaces — live two-device sync | ⛔ Not built | Phase 6 exit criterion. No Realtime |
| Lifecycle (saved → completed) | ✅ Done | Backend verified live; the mobile strip has never run on a device |

---

## Acting

| Feature | Status | What's missing |
|---|---|---|
| Recipe → shopping list | ✅ Done | Verified against two real recipes: garlic 7 cloves (3+4), olive oil 4 tbsp (2+2) |
| Shopping list screen | 🟠 Unverified | Optimistic tick-off, clear-checked. Never run on a device |
| Workout → routine | ⛔ Not built | Spec names it; only the one Act exists |
| Restaurant → navigate / vote | ⛔ Not built | — |
| Any other type-specific Act | ⛔ Not built | — |

**One of the promised Acts exists.**

---

## Monetisation

| Feature | Status | What's missing |
|---|---|---|
| RevenueCat webhook + entitlements | ✅ Done | Six webhook cases driven live. Entitlement derived from expiry, with dedupe and ordering guards |
| Free-tier caps | ✅ Done | Enforced in the worker and at the Act controller. Deliberately **off** by default until there is a paid tier to escape to |
| **RevenueCat SDK in the app** | 🟠 Unverified | `react-native-purchases` 10.9.0, behind a three-way adapter seam (`app/src/billing/`) split by Metro platform extension. Measured, not assumed: the **web** bundle contains 0 references to the SDK and the **Android** bundle contains it. `Purchases.configure`/`logIn`/`logOut` are wired to the Supabase session — but **the SDK has never run**, because that needs a dev client on a device and there is no Android SDK here |
| **Paywall** | 🟠 Unverified | `/paywall`, reached from the Settings plan card ("Upgrade", or "Manage" for a Pro user, which is the only route to Restore). Benefits, plan rows, the server's own usage counters, restore, and a billing disclosure that changes for a lifetime plan. Driven end to end through headless Chrome in mock mode — 31/31 CDP checks including the buy → confirm → plan-card-flips loop. Never run on a device, and never against a real store |
| **Server confirmation of a purchase** | 🟠 Unverified | The client never claims Pro from the SDK: a completed purchase polls `GET /v1/me` on a bounded ~21s schedule and only the server's answer is rendered. A wait that expires is its own state (`pending`, "your purchase went through, still confirming"), never an error — the user has been charged. Exercised in mock mode only; no real webhook has ever raced it |
| **Store products / subscriptions** | ⛔ Not built | No RevenueCat dashboard project, entitlement, products or current offering; no Play Console subscription. The paywall renders its "nothing on sale yet" state until these exist. External clock — see [play-store-release.md §1.7](play-store-release.md) |
| **RevenueCat SDK keys** | ⛔ Not built | `EXPO_PUBLIC_REVENUECAT_*` are present but **empty** in `eas.json`. Empty disables purchases (deliberately — the paywall explains itself rather than crashing), so a build shipped today has a paywall that cannot sell |
| Sandbox purchase | ⛔ Not built | Nothing here has met a real store. The highest-value single test remaining |

**The client half now exists but has never met a store.** The code path is
complete and driven; what is missing is entirely outside this repo — a
RevenueCat dashboard, Play Console products, the two SDK keys, and the webhook
secret on Render. Without that last one in particular, a real purchase never
becomes Pro and the app waits forever. This is a RevenueCat hackathon, so the
sandbox purchase is a submission requirement, not a feature.

---

## Notifications & digests

| Feature | Status | What's missing |
|---|---|---|
| Push notification on `ready` | ⛔ Not built | No `expo-notifications`, nothing server-side. Less load-bearing than it was: `SavesProvider` now polls whatever the *store* says is still `processing` (feed-driven, so a save made on another device advances too), with a timeout. The notification is still the intended signal — polling is a stopgap that stops after the timeout |
| Weekly digest | 🟠 Unverified | Built and deployed 2026-08-06 — `GET /v1/digest`, generated on demand, Home's tile wired to it. `V8__digests.sql` confirmed applied live and `generate_digest` confirmed registered in the job runner. No longer sample content, but the endpoint itself has not yet been hit against a real week of saves |
| AI Project Builder | ⛔ Not built | In the spec's budget planning; no code |

---

## The app on a real device

Only three things have been **seen working on a phone**:

| Feature | Status |
|---|---|
| Sign in | ✅ Done |
| Home feed from the live API | ✅ Done |
| Bottom navigation | ✅ Done — after it painted nothing on the first run |

Everything else app-side is 🟠 **Unverified**: Library, Spaces, save detail,
search, group screens, the Settings morph, the capture sheet, haptics, the
shopping list. All of it typechecks, bundles and renders in a browser — which is
precisely the combination that missed the missing navigation bar.

The Capture sheet's Yoga fix is **unverified specifically**, because Chrome
renders that screen correctly whether the fix is right or not.

---

## Data layer — how the app reads and writes

The plan is [docs/local-first.md](local-first.md). **L1–L5 all landed
2026-08-09; that plan is complete.**

| Feature | Status | Where it stands today |
|---|---|---|
| Local database | ✅ Built (L1) | `app/src/local/` — a typed store with a table-scoped change bus, `sqliteStore` on native and an AsyncStorage-snapshot `memoryStore` on web (Metro platform extension, so the web bundle contains no `expo-sqlite` at all). `itemStates` is normalised into its own table so a `/search` or `/related` payload cannot erase a ticked checkbox |
| Render-from-cache startup | ✅ Built (L1) | `SavesProvider` reads the store through `useLive` and holds the **whole** library, not page 0. Confirmed over CDP: a second load paints the feed, Continue rail, groups grid and Spaces strip within 2.5s with no spinner |
| Reads served locally | ✅ Built (L2) | Home, Library, Spaces, Space detail, Save detail, Collection detail, Group detail, Shopping list, Settings and Workout compare all read the store. `SpacesScreen`'s per-focus N+1 is gone — members come from the store, fetched by the sync engine off the render path |
| Groups / collections derived on-device | ✅ Built (L2) | `app/src/groups/tree.ts` + `app/src/knowledge/facets.ts` port `GroupService`/`KnowledgeFacets`; `@/collections/merge` already ported `CollectionService`. `GET /v1/groups` and `GET /v1/collections` are no longer called (both stay implemented, for `mockRepository` parity) |
| Offline writes | ✅ Built (L3) | `app/src/local/outbox.ts` — every write lands in the store and drains from a durable queue with backoff. The revert logic is *gone*, and its absence is the deliverable: a failed request used to be silently undone, which is indistinguishable from "your tap never registered". A write the server rejects is surfaced on Settings (`PendingWrites`) rather than rolled back. Last-write-wins is the real semantics, not a compromise — every one of these endpoints is a documented full replace, so a retry is harmless with no conflict-resolution code |
| Delta sync | ✅ Built (L4) | `V15__sync.sql` + `GET /v1/sync` over seven tables on one shared timestamp cursor. The plan's `(updated_at, id)` keyset could not be built — four of the seven have composite PKs and no scalar id to tie-break on — so `SyncWindow` never ends a page *inside* a timestamp group. Full pulls stay, but only on pull-to-refresh, because `replaceAll` reaping is the only way to recover from a missed tombstone. **`GET /v1/sync` has still never been called over HTTP** |
| Local full-text search | ✅ Built (L5) | `saves_fts` (FTS5 on native, linear scan on web) — the last read path that could only answer from the network. Deliberately not debounced locally and every local term is a prefix, so the local result set is a *superset* of the server's, which is what makes appending local-only hits safe. A failed server search now leaves the local results on screen and says why, instead of replacing everything with an error card that offline reads as "you have nothing". The FTS table is created outside the main schema batch and allowed to fail, so a SQLite build without FTS5 loses search rather than the entire local database |

Two traps that shaped the built layer, and still bind anything added to it:

- **`SaveResponse.itemStates` is populated by only three endpoints.**
  `/search`, `/related`, `/spaces/{id}/saves` and `/groups/{id}/saves` use the
  1-arg `SaveResponse.from` and omit it. Any cache that writes one of those
  responses over a stored save erases every ticked checkbox. Solved by
  normalisation rather than a merge rule: `putSaves` strips the field before
  writing and only ever *adds* to `item_states`, so no call site has to
  remember anything.
- **expo-sqlite's web support is alpha** and needs Metro WASM config plus
  COOP/COEP headers for `SharedArrayBuffer` — i.e. adopting it naively would
  break headless Chrome on expo-web, which is this project's entire visual and
  interaction test harness. Hence two store implementations split by a Metro
  platform extension; the built web bundle contains no `expo-sqlite` symbols
  at all.

---

## Infrastructure

| Feature | Status | What's missing |
|---|---|---|
| Schema + migrations | ✅ Done | Applied against Supabase PG 17.6 |
| Auth (Supabase JWT, ES256) | ✅ Done | Both accept and reject paths |
| Job queue + runner | ✅ Done | Claim, exclusion, three error paths, stale sweep |
| Deployment (Render + Docker) | 🟠 Unverified overall, YouTube path now ✅ | Deployed and running, confirmed live via the Render API: current deploy is `66cca73`, `/actuator/health` returns `UP`, memory sits ~355–373 MB of a 512 MB limit at idle. YouTube extraction is confirmed working end to end via `RapidYtClient` (see above). New finding from that same check: `WEAVR_DB_POOL_MAX=3` saturated under manual test traffic — one request timed out after 12.5s waiting for a connection. Still true regardless: the image **must** install `yt-dlp`, `ffmpeg`, `tesseract` *and* `tesseract-ocr-eng` — a plain JRE has none, and missing language data reads every frame as nothing, silently |
| Keep-warm cron | 🟠 Unverified | Supabase pauses after ~7 days idle; a paused project during judging is a demo-day failure |
| Integration tests (Testcontainers) | ⛔ Not built | Unit tests cover validation and enum mapping only |
| Store records / Apple enrolment | ⛔ Not built | Phase 0, never closed. Not code, and the lead time is external |

---

## Known defects

| Defect | Impact |
|---|---|
| `Idempotency-Key` race recovery re-reads inside an aborted transaction | Would fail rather than return the existing save. Never observed — the pre-check catches every non-concurrent replay |
| ~~`SecurityConfig` accepts ES256 only~~ | **Fixed 2026-08-02.** Both RS256 and ES256 are now accepted |
| ~~Gemini fallback model (`gemini-2.5-flash`) 404s on every call~~ | **Fixed 2026-08-05.** Deprecated for this project's key; confidence-retry and OCR vision-escalation were both failing closed with no visible symptom. Now `gemini-3.6-flash` |
| ~~`WEAVR_DB_POOL_MAX=3` saturates under light concurrent traffic~~ | **Fixed 2026-08-05.** Bumped to 5; not load-tested against real concurrent-save volume |
| Android share worker can hold a stale access token | A silently dropped share, after the user already saw "Saved" |
| Capture failure after dismissal has no surface | Warns to console; the save never appears. Needs a toast |
| ~~Every screen refetches from zero on mount~~ | **Fixed 2026-08-09** by L1/L2 of [docs/local-first.md](local-first.md). Reads are served from the local store; `SpacesScreen`'s per-focus N+1 is gone. Writes are still network-first |
| `sqliteStore.ts` has never run | Web resolves the memory shim and there is no device or emulator here, so the store *contract* is verified and its SQL is not |
| ~~No optimistic write survives a crash~~ | **Fixed 2026-08-09** by L3's outbox. Every write goes through `@/local/writes` — store patch plus a durable queued op with a stable idempotency key — so a kill between the local flip and the server echo loses nothing. `LifecycleStrip` reconciles too, via `writeSaveLifecycle`. What replaced the old failure is *visible*: a write the server rejects is surfaced on Settings rather than silently reverted |

---

## Build order

Ordered by what happens if it is done late, not by size. Two developers, so the
tiers overlap — see the note at the end.

### Tier 0 — do first: hours of work, and each one can change the plan

Cheap tests with the highest information per minute. Doing these after building
around their assumptions is how a week gets thrown away.

1. ~~**Verify the real Gemini RPD** in AI Studio.~~ **Done 2026-08-05.** Primary
   (Flash-Lite) really is 500 RPD — the feared 250 didn't happen. Surprise the
   other way: the fallback/vision tier (Flash) is only **20 RPD**, an order of
   magnitude below the ~250 assumed in CLAUDE.md's capacity math. That pool
   was never going to add meaningful save throughput; it only ever needed to
   cover the confidence-retry backstop and OCR vision escalation, and 20/day
   has been enough for that so far. See CLAUDE.md's request-budget section
   for the full per-model table.
2. ~~**Run a real Instagram Reel and a real TikTok through the cascade.**~~
   **Instagram half done 2026-08-05** — 7 of 7 real public Reels cleared the
   live cascade with no auth wall, including a 6-in-a-row burst with no
   slowdown. TikTok is still untested. Private accounts and higher volume are
   still open, but the worst-case "auth-walled, demo narrative changes"
   outcome did not happen across 7 tries.
3. ~~**`SecurityConfig` RSA line.**~~ **Done 2026-08-02.** Both algorithms are
   accepted, so a Supabase key rotation to RSA no longer takes auth down with a
   failure message that points nowhere.

### Tier 1 — external clocks, start immediately

Nothing here is code, and none of it can be compressed later.

4. **Apple Developer enrolment** and App Store Connect records.
5. **Google Play records.**
6. **RevenueCat dashboard**: products, entitlements, offerings.

These gate TestFlight and sandbox purchases, which gate the submission.

### Tier 2 — submission-blocking

7. ~~**`react-native-purchases` + paywall**~~ **Built 2026-09-04** — SDK, adapter
   seam, paywall route, identity wiring and the server-confirmation wait, all
   driven end to end in mock mode (31/31 CDP checks). **A real sandbox purchase
   is still open, and so is everything it depends on**: the RevenueCat dashboard
   (entitlement, products, a *current* offering), Play Console subscriptions, the
   two `EXPO_PUBLIC_REVENUECAT_*` keys in `eas.json` (present but empty), and
   `WEAVR_REVENUECAT_WEBHOOK_SECRET` on Render — without that last one no
   purchase ever becomes Pro. See [play-store-release.md §1.7](play-store-release.md).
8. **Deployment.** The image must install `yt-dlp`, `ffmpeg`, `tesseract` *and*
   `tesseract-ocr-eng`; a plain JRE has none, and missing language data reads
   every frame as nothing, silently. Plus the keep-warm cron — Supabase pauses
   after ~7 days idle, and a paused project during judging is a demo-day
   failure.

### Tier 3 — the core promise, and the highest technical risk

9. **Move the session to a shared Keychain**, storing the *refresh* token. A
   prerequisite for the next item, and flagged as painful to retrofit — which is
   the argument for doing it before more is built on AsyncStorage.
10. **The iOS share extension.** Silent capture is the entire pitch; without it
    the product is a bookmarking app you have to open. Also the riskiest thing
    remaining, which is a second reason to start it early rather than late.
11. **Push notifications.** Sounds like polish, is not: the app deliberately
    does not poll for a save reaching `ready` *because* the notification is
    meant to be that signal. Without it every save sits on "Processing" until
    the user pulls to refresh.

### Tier 4 — make the app real

12. **A device pass over every screen.** Library, Spaces, save detail, search,
    groups, the Settings morph, the capture sheet. All of it typechecks,
    bundles and renders in a browser — the exact combination that missed a
    missing navigation bar.
13. **Android silent capture on a device.** Built, never executed.
14. **Screenshot / photo capture with Tier 0 on-device OCR.** The highest-value
    capture tile after links: it removes an entire category of saves from the
    expensive vision path, and screenshots are a primary way people save things.

### Tier 5 — the features that currently look done and are shells

15. ~~AI groups: a generator, an endpoint, persistence.~~ **Done** —
    `GET /v1/groups` derives the tree from `knowledge_type` plus the facets the
    classify call already extracted, so it costs no Gemini request and cannot
    go stale. What remains is judging whether facet-derived folders are good
    enough, or whether a periodic clustering pass is worth the requests.
16. ~~**Weekly digest endpoint and generator.**~~ **Built 2026-08-06** — no
    longer the last sample content. `GET /v1/digest` generates on demand
    (enqueued the first time a week has no cached row), not by a scheduler,
    which is the "budget it explicitly" concern resolving itself: nobody pays
    for a digest nobody opens, and one Gemini call per user per week draws
    from the same primary pool as saves. Full backend suite green, app
    typechecks and bundles. **Not yet run against the live deploy or a real
    week of saves** — same "built, unverified live" gap as ASR and
    enrichment before it.

### Tier 6 — quality, and the guards against silent failure

17. **The 30-Reel eval set**, and thresholds measured from it rather than
    guessed. Both failure directions are silent today.
18. **Live-verify ASR, link/PDF, enrichment and duplicate detection.** Four
    features whose only evidence is mocked tests.
19. **Testcontainers**, and the `Idempotency-Key` race fix that needs a test
    which actually races two requests.
20. **A toast surface** for failures raised after a sheet dismisses, and a
    refresh-token exchange inside the Android share worker.

### Tier 7 — scope beyond a working MVP

21. Workout → routine, restaurant → navigate/vote, and the other Acts.
22. Spaces live two-device sync (Realtime).
23. The remaining capture tiles — voice memo, scan, upload, text note.
24. AI Project Builder.

### Running two people against this

Tiers 2 and 3 are the natural split: one developer on monetisation and
deployment, one on the Keychain move and the iOS extension. They share no files.
Tier 0 is a morning's work for one person and should happen before either
starts — item 2 in particular can redirect what "the demo" even means.

---

## Summary

**32 done, 20 unverified, 0 mock-only, 24 not built** (76 features).

Under a looser bar the first two columns merge and this reads as 52 of 76 —
roughly how it feels while writing it, and close to twice what has actually
been proven.

*Changed 2026-08-10: the local-first plan (L1–L5) is complete, so offline
writes, delta sync and local search moved from not-built to built; knowledge
collections gained four rows (K1–K2 done and live-verified, K6–K7 unverified,
K4's merge/rename UI not built); the weekly digest is no longer sample
content; and "the app deliberately does not poll" is no longer true. Counts
above are recounted from the tables themselves rather than adjusted by hand.*

*Changed since the first count (2026-08-01): AI groups, subgroups and their
endpoint moved from mock-only to done, so nothing is mock-only any more, and
the `SecurityConfig` defect is fixed. Two counting errors are also corrected —
the first revision said "21 done … 38 of 65", which counted the four legend
rows as features, and the capture section claimed both "nine of twelve" and
"eleven of twelve" tiles when there are eight, of which one works.*

*Changed 2026-08-05: YouTube captions + metadata moved from unverified to
done — not because yt-dlp's own path got fixed on Render (it didn't; both
attempted fixes were tested live and confirmed to fail), but because
`RapidYtClient` bypasses YouTube directly and was confirmed live by two real
saves completing the full pipeline on Render's own logs. Instagram Reels also
moved to done the same day: a real public Reel cleared yt-dlp's plain probe
on Render with no auth wall at all, the opposite result from YouTube on the
same infrastructure. Real Gemini RPD also moved to done: primary is
confirmed 500 as assumed, but the fallback/vision pool is 20, not the ~250
the capacity math in CLAUDE.md had assumed.*

*Changed 2026-08-06: the weekly digest moved from not-built to unverified —
`GET /v1/digest`, on-demand generation, no scheduler, and Home's tile reads
it instead of a hardcoded string. That was the last sample content anywhere
in the app. Two live bugs were also found and fixed the same session:
`gemini-2.5-flash` (the fallback model) was silently 404ing on every call —
deprecated for this project's key — and `WEAVR_DB_POOL_MAX=3` was saturating
under ordinary test traffic.*


Done and verified: the **backend spine** — schema, auth, saves, the job runner,
YouTube caption extraction, the Gemini classify call, embeddings, hybrid search,
the shopping-list Act, Spaces, AI groups, and the RevenueCat webhook.

Not done: **the entire client half of monetisation**, **the iOS share extension**
(the core promise), **push notifications**, **seven of eight capture tiles**,
**three of four Acts**, and **verification of nearly everything on a device** —
plus Instagram and TikTok extraction, which is what most users would actually
be sharing.
