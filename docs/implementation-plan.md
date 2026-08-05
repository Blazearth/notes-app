# Implementation Plan

Combined backend + mobile plan for the Shipaton window (Aug 1 – Sep 30, 2026). Two developers.

Read alongside [CLAUDE.md](../CLAUDE.md) (architecture and constraints) and [competitive-analysis.md](competitive-analysis.md) (the ranked steal list, referenced below as **CA#n**).

---

## Where we are — 2026-07-30 (two days before the window opens)

| Phase | Built | Exit criteria met |
|---|---|---|
| 0 — Pre-flight | repo, Supabase project, decisions closed | ⬜ store records, Apple enrolment, **real Gemini RPD still unverified** |
| 1 — Foundation | schema, auth, `POST`/`GET /v1/saves`, whole Expo app | 2 of 4 — backend closed, mobile blocked on a dev build |
| 2 — Ingestion cascade | job runner, cascade steps 1–4 — captions, metadata, **verified against a real yt-dlp from a residential IP**; ASR (Groq Whisper) and readable-text/PDF extraction, unit-tested but not live-verified | 1 of 4 — captions proven end to end from a dev machine, but Render's datacenter IP hits YouTube's bot check on every probe, a gap the residential-IP live test can't see. Cookies, a player-client override and a RapidAPI primary path (`RapidYtClient`, 2026-08-05) have landed against it, none confirmed live yet — see [CLAUDE.md](../CLAUDE.md). ASR/link/PDF are code-complete but unproven live. Android's silent-capture receiver is built (config plugin, never run on a device); the iOS share extension — the actual highest-risk spike — is still unbuilt |
| 3 — AI pipeline v1 | Gemini classify-and-extract call, budget layer, response-schema registry, **verified live against the real API**; type-specific mobile cards | 3 of 4 — a recipe Reel with captions becomes a structured card, budget exhaustion queues rather than fails, the app shows real cards; blocked-download → `unusable` unverified live |
| 4 — OCR tier | frames, tesseract, cross-frame voting, escalation gate, sharpness ranking, Flash-vision Tier 2, **live-verified against real ffmpeg + tesseract** | 1 of 4 — an overlay-only card extracts with no vision call; **the eval set does not exist, so every threshold is still a guess**, and both mobile criteria are untouched |
| 5 — Product | **complete server-side**: search, the one Act, lifecycle, free-tier caps and the RevenueCat webhook — all verified live. Enrichment (a Phase 3 leftover) landed alongside | 4 of 6 — everything but the store listings and a real sandbox purchase, neither of which is code |
| 6 — Spaces | CRUD, roles, revocable invites, space feed, comments, votes, activity, embedding-similarity duplicate detection. Authorisation verified live. **AI groups landed 2026-08-02** — `GET /v1/groups`, a derived tree costing no Gemini request | 2 of 3 — the two-device live-sync criterion needs devices; Realtime sync is not built |
| 7–8 | — | — |

**The pattern to notice: writing code is running well ahead of proving it.** The
backend is roughly a phase early, and the app is far ahead of what Phase 1 asked
for. Two things stand between us and a demo, and neither is code:

1. **No dev build exists**, so nothing in the app has run on a device.
2. **The Gemini RPD number is still unverified** — Phase 0 flagged it, and it
   changes capacity planning materially at 250 vs 500.

Each is small and unblocks a whole phase. Do them before writing more pipeline.

**The third item on this list is now closed, and it is worth noting what closing
it cost.** yt-dlp and ffmpeg are installed, and the first run against a real
video found three defects in code that already had 27 passing tests — a
`--sub-langs` regex that expanded to 29 downloads and drew an HTTP 429, an exit
code that discarded captions already written, and caption selection ranked by
file size, which preferred the bloated auto-generated track over the human one.
None were visible to a mocked process. Details in
[testing.md](testing.md#what-running-the-real-binary-found); the lesson applies
directly to the two items still open above.

**2026-07-31: the Gemini call ran live and Phase 3's backend half closed, ahead
of schedule.** It produced real structured saves, watched happen twice, and
the same "mocked process hid real bugs" lesson repeated exactly: a UTF-8
decoding bug (Gemini's `Content-Type` carries no charset; reading the response
as `.body(String.class)` let Spring guess wrong instead of following the JSON
spec's UTF-8 default) was invisible through the code's own construction and
only surfaced on a live call, fixed by reading raw bytes and letting Jackson
decode. That fix and the rest of the classify path — model routing, budget
guard, response-schema registry — had zero tests before this session; they
have 25 now. **Save-level idempotency also landed** (`Idempotency-Key` header,
`V2__save_idempotency.sql`), earlier than planned below — it was scoped for
"this phase, with the extension" but shipped with the Gemini work instead,
since nothing about it depends on the extension existing. And the mobile
type-specific card work Phase 3 scopes below is done: `SaveCard` renders
recipe/movie/place layouts on the Home feed. Still open: OCR (Phase 4), a
Gemini call against a genuinely blocked/unusable download (only tested with a
scripted `unusable` response so far), and everything mobile still waits on a
dev build.

**Same day, later: steps 3 and 4 of the Phase 2 cascade landed** — ASR (yt-dlp
audio download → ffmpeg 16 kHz mono downmix → Groq Whisper) and the
non-video branch (readable-text extraction for plain links via Readability4J,
PDFBox for PDFs). All three are new external dependencies with real API
surface to get wrong, so each got the same encoding-bug-shaped scrutiny the
Gemini fix earlier today came from: `GroqClient` reads its response as raw
bytes, not `String`, from the start — the mistake was made once, on Gemini,
and applied everywhere else preemptively rather than waiting to hit it a
second time. `LinkExtractor` hands Jsoup raw bytes with no assumed charset
for the same reason; an arbitrary web page's declared encoding lives in an
HTTP header *or* a `<meta>` tag, and guessing either wrong is the same
mojibake shape. 46 new tests, all mocked (`MockRestServiceServer` for Groq
and the PDF/link downloads, mocked `ExternalProcess` for ffmpeg) — **nothing
here has hit the real Groq API or downloaded a real audio track**, which is
exactly the gap that found three defects in the yt-dlp caption path and nine
tests' worth of encoding assumptions in Gemini. Whether that repeats a third
time here is unknown until `WEAVR_GROQ_API_KEY` is set and something actually
runs it. Two branching decisions worth remembering: an "unsupported URL" from
yt-dlp's probe is no longer a final failure — it now means "not a video
platform" and falls through to link extraction — and a `.pdf` URL skips
yt-dlp entirely rather than wasting a probe on it. Also fixed in passing:
`ProcessSaveHandler` was hard-failing every `IMAGE` save with
`unsupported_source_type`, contradicting `SourceType`'s own doc comment that
an image save carries on-device OCR text — it now takes the same path as
`TEXT`.

---

## The shape of this plan

**Ship publicly at the end of Week 5, not Week 8.** The Grand Prize is judged on traction *during* the event (spec §13), and #BuildInPublic scores on visible iteration. A narrow app in users' hands on Sep 1 with four weeks of public iteration beats a feature-complete submission on Sep 29. Everything below is sequenced so that **Weeks 1–5 build a releasable product** and **Weeks 6–8 are ship-measure-iterate**.

**Backend leads by roughly one phase.** The pipeline is the risky, unknown part and it's testable over `curl` with no app at all. But mobile cannot start in Week 6 — the iOS share extension and store setup have hard lead times. So each phase carries both.

**Every phase ends demoable.** If a phase can't be shown working, it isn't done.

### Critical path

```
bundle ID ──┬─▶ EAS dev client ──▶ share extension ──▶ silent capture
            ├─▶ App Store Connect record ──▶ IAP products ──▶ RevenueCat ──▶ paywall
            └─▶ App Group + Keychain group

Supabase project ──▶ Flyway V1 ──▶ jobs table ──▶ pipeline ──▶ feed API ──▶ app feed
```

Two things gate everything and must be resolved in Phase 0: **the bundle ID** and **the Supabase project**. Nothing downstream can start without them.

### Division of labor

| | Dev A (backend-leaning) | Dev B (mobile-leaning) |
|---|---|---|
| Owns | Spring service, pipeline, schema, Gemini budget, search | Expo app, share extension, RevenueCat, store presence |
| Also does | The RevenueCat webhook and entitlement gating | The eval set (§Phase 4) — it's product judgement, not code |

Swap for review. Neither person should be the only one who understands the pipeline or the extension.

---

## Phase 0 — Pre-flight (Jul 29–31, before the window opens)

Nothing here is code. All of it blocks something later, and several items have external latency you cannot compress.

| Task | Why it's here | Latency risk |
|---|---|---|
| **Lock the app name** — App Store + Play + domain check | Blocks bundle ID, which blocks everything | — |
| **Enrol in Apple Developer Program** ($99) | Can take **24–48 h or longer** to approve. Start today. | ⚠️ High |
| **Google Play Console account** ($25) | New developer accounts face additional verification | ⚠️ Medium |
| **Create the Supabase project** — pick region near your Spring host | Blocks V1 migration | — |
| **Verify actual Gemini RPD** in [AI Studio](https://aistudio.google.com/rate-limit) | 250 vs 500 vs 1000 changes capacity planning materially | — |
| **Decide open decisions #2–#9** (CLAUDE.md) | Several are baked into V1 DDL | — |
| **Create the monorepo** — `/api` (Spring), `/app` (Expo), `/docs` | — | — |

**Decisions to close now** (from CLAUDE.md's open list): embedding model + dimension (#2, → `gemini-embedding-001` at 1536), v1 knowledge types (#3, → recipe/movie/place/`other`), hosting + RAM ceiling (#4), auth providers incl. Sign in with Apple (#6), job queue = Postgres `SKIP LOCKED` (#8), monorepo (#9).

**Exit criteria:** bundle ID reserved on both stores · Apple enrolment submitted · Supabase project provisioned · real RPD number written into CLAUDE.md · repo scaffolded.

> **Do not publish a public build before Aug 1.** The rules require the first public version to fall inside the window. TestFlight and internal testing are fine.

---

## Phase 1 — Foundation (Week 1: Aug 1–7)

**Goal:** an authenticated request from a real device reaches Spring Boot and writes a row.

### Backend

Package structure:

```
com.<app>.api
├── config/      security, datasource (2 URLs!), flyway, async pools
├── auth/        JWT resource server, CurrentUser resolver
├── save/        controller, service, entity, repository
├── space/
├── job/         queue, claim, retry, JobRunner
├── pipeline/    acquire · transcribe · ocr · extract · enrich · embed
├── budget/      BudgetApproved token, daily counter
├── search/      fts · vector · rrf
├── billing/     RevenueCat webhook, entitlements
└── notify/      Expo push
```

**Flyway V1** — the one-way door. Get these right now:

```sql
create extension if not exists vector;

profiles(id uuid pk references auth.users, display_name, avatar_url,
         revenuecat_customer_id, created_at)

spaces(id, name, type, owner_id, created_at)
space_members(space_id, user_id, role, joined_at,
              primary key (space_id, user_id))          -- CA: composite PK
create index on space_members(user_id);

saves(id, user_id, space_id null, source_type, source_url, raw_caption,
      media_storage_path, status, knowledge_type, confidence,
      structured_data jsonb, embedding vector(1536),
      lifecycle_status, model_used, created_at, updated_at)
create index on saves(user_id, created_at desc);
create index on saves(space_id, created_at desc);

save_stages(save_id, stage, payload jsonb, created_at,
            primary key (save_id, stage))               -- CA#4 stage cache

jobs(id, type, payload jsonb, status, priority int, group_id text,
     idempotency_key text unique, attempts int, max_attempts int,
     run_after timestamptz, claimed_at, claimed_by, last_error, created_at)
create index on jobs(status, run_after, priority desc);  -- CA#3

gemini_calls(id, save_id, model, purpose, input_tokens, output_tokens,
             confidence, outcome, created_at)            -- CA: observability
usage_counters(user_id, period_start, saves_used, acts_used)
subscriptions(user_id, revenuecat_customer_id, entitlement, status, renews_at)
```

**Two datasource URLs.** `spring.datasource.url` → Supavisor **transaction** pooler (`prepareThreshold=0`, Hikari max 5–10). `spring.flyway.url` → **session** pooler or direct. Mixing these breaks migrations under concurrency, and it breaks *later*, not on the first run.

Auth: `spring-boot-starter-oauth2-resource-server` against Supabase's JWKS. A `@CurrentUser` argument resolver extracting `sub`. Authorization lives in the service layer — Spring connects as service role, so RLS is not your API boundary.

`POST /v1/saves` accepts `{source_type, source_url|text}`, writes a `saves` row with `status=processing`, enqueues a job, returns `202` with the save ID. No pipeline yet.

### Mobile

Expo app with **EAS dev client** from day one (`expo-share-extension` and `react-native-purchases` are native — Expo Go will never work). Supabase client, anonymous auth, JWT attached to API calls. One screen: paste a URL, POST it, see the row come back.

**Done ahead of this, on paper:** the UI shell, the theme and personalisation layer, the Supabase client, email/password auth, the Home feed from `GET /v1/saves`, and the Capture sheet's Paste Link tile posting to `POST /v1/saves`. All of it typechecks and bundles — see [app/README.md](../app/README.md).

**What is actually left is the hard half: making a dev build and running it.** Nothing above has executed on a device, so the exit criterion is untouched. Two deviations from this plan to note when it does run:

- **Auth is email/password, not anonymous.** Anonymous sign-in needs a dashboard toggle that is not enabled, and password grant is the path already proven end-to-end. Cost: new accounts need email confirmation through the admin API before they can sign in.
- **The session is in AsyncStorage, not a shared Keychain.** That is fine until Phase 2, where step 3 of the share-extension spike depends on it. Moving it — and storing the *refresh* token — is the retrofit CLAUDE.md warns is painful.

### Parallel

App Store Connect app record. Play Console app record. RevenueCat project created and linked to both.

**Exit criteria:** ✅ `curl` with a real Supabase JWT creates a save (2026-07-30) · ⬜ dev client runs on a physical device and creates a save · ✅ Flyway migrates cleanly against Supabase · ⬜ both store records exist.

**Risks:** Apple enrolment still pending — if so, do Android first and keep iOS moving on the simulator.

**Remaining:** the two mobile-side criteria. The backend half of Phase 1 is closed.

---

## Phase 2 — Ingestion cascade, no AI (Week 2: Aug 8–14)

**Goal:** any shared URL produces a text blob. Zero Gemini calls.

### Backend

**Job runner first** — ✅ **done** (`api/src/main/java/com/weavr/api/job/`). `SELECT ... FOR UPDATE SKIP LOCKED`, honouring `priority` and `run_after`, concurrency capped at **1–2** on a bounded platform-thread pool, plus a stale-claim sweep so a crashed worker's jobs return to the queue. Verified against live Supabase by draining the job the Phase 1 create path left behind.

Three error paths, not two — the extra one earns its place (CA#2):
- `RetryableJobException`, and anything unrecognised → increments `attempts`, exponential backoff (30s, 2m, 8m, 32m, capped at 1h)
- `RetryAfterException(delay)` → sets `run_after`, **does not** increment `attempts`. This is what keeps a Gemini quota rejection from burning the retry budget in Phase 3.
- `PermanentJobException(code, userMessage)` → fails immediately without spending retries, and pushes a user-safe message onto the save. An unsupported URL should not be retried five times over an hour, and with silent capture the feed is the only place the user ever finds out.

**Deviation from CA#3:** fairness is a per-group *exclusion* (a group with a job already running is skipped) rather than true round-robin. Postgres rejects `FOR UPDATE` in a query with window functions, so a `row_number()` ranking would cost the skip-locked property. At a pool of 1–2 the exclusion gives the same anti-monopoly result; revisit if concurrency ever rises.

**The cascade** (CLAUDE.md § text-extraction cascade) — `api/src/main/java/com/weavr/api/pipeline/`:

| Step | State |
|---|---|
| 1. Platform captions — `--skip-download --write-subs --write-auto-subs` | ✅ built, verified live |
| 2. Post metadata — title, description, uploader | ✅ built, verified live |
| 3. ASR — `ffmpeg` → 16 kHz mono → Groq Whisper | ✅ built, 🟡 unit-tested only — no real Groq call made yet |
| 4. Readable-text for links (Readability4J); text extraction for PDFs (PDFBox) | ✅ built, 🟡 unit-tested only — no real page/PDF fetched yet |

**Steps 1 and 2 are inverted from the order above, deliberately.** The metadata
probe is `--dump-single-json`: one call, no files, no media — and its response
*also* lists the available caption tracks (CA#14), so it doubles as the discovery
step. Captions are then fetched only when the probe says they exist. Running the
cheaper call first costs nothing and often ends the cascade there.

**Steps 1 and 2 are now verified against a real yt-dlp** (2026.07.04) by the
opt-in `YtDlpLiveTest` — argument lists, JSON field names and subtitle-file
discovery included. That run found three defects, all fixed and pinned by tests;
the two worth remembering before step 3 is written on top are that
**`--sub-langs` entries are regexes** matching yt-dlp's synthesised
`<source>-<target>` translation tracks, and that **auto-generated VTT is several
times larger than an uploaded track while carrying less text**, so caption files
must be ranked on parsed prose rather than size. Full write-up in
[testing.md](testing.md#what-running-the-real-binary-found).

Only YouTube has been exercised live. Instagram, TikTok and Reels remain
unproven, and Instagram increasingly requires authentication — treat an
unauthenticated failure there as an acceptable outcome rather than reaching for
cookies.

Process hygiene, non-negotiable (these hang rather than fail) — ✅ done in
`ExternalProcess`, and each one proven by reproducing the hang with a real
spawned JVM rather than a mock:
- Drain stdout **and** stderr on separate threads *(test floods 1 MB down each; sequential draining deadlocks)*
- `waitFor(timeout)` + `destroyForcibly()` *(test uses a child that never exits)*
- Output capped, and the pump keeps draining past the cap — ceasing to read refills the pipe and re-blocks the child
- stdin closed immediately, so a tool that prompts cannot wait forever
- Step 3 (ASR) landed 2026-07-31: `-f bestaudio --download-sections "*0-90"`
  bounds the download; `-f 'worst[height>=360]'` (video frames for OCR) is
  still Phase 4. **Deviation from the "pipe to ffmpeg" plan**: audio lands in
  a temp file and ffmpeg reads that, rather than a `yt-dlp | ffmpeg` shell
  pipeline — audio at a 90s cap is small enough that a temp file costs
  nothing worth the extra process-wiring complexity a real pipe needs in
  Java. `delete in finally` — done (`TempDirs`, shared with the caption path).

Steal from the teardown: ✅ caption-language discovery from `subtitles` +
`automatic_captions` (CA#14) · ⬜ `ffprobe` audio-stream probe before ASR (CA#13) —
still open; the download is attempted unconditionally and a missing audio
track is just an empty result, not a pre-flight check · ✅ yt-dlp error →
user-message mapping table (CA#9), defaulting *unrecognised* failures to
retryable because broken extractors are routine, now shared by the ASR
download path too (`YtDlpErrors.toException`) · ✅ each stage written to
`save_stages` (CA#4).

**A save now reaches `ready` (or `failed`).** Extraction produces text;
`classify_save` (Phase 3, landed 2026-07-31 — see the note above) turns it into
a knowledge type and structured fields with the single Gemini call. The text
still parks in `save_stages` first, same as always — that part of the sentence
was never wrong, only the "and the save waits" half of it.

✅ **Save-level idempotency on `POST /v1/saves` — done 2026-07-31, ahead of the
extension.** The *job* was already deduped by save id; the save itself was not,
so a retry created a duplicate. Fixed with a client-supplied `Idempotency-Key`
header and a partial unique index on `saves (user_id, idempotency_key)`
(`V2__save_idempotency.sql`) — a repeated key returns the existing save,
including under a concurrent-retry race. No caller sends the header yet: the
in-app Paste Link tile doesn't need it (a second tap is a fresh user action,
not a retry), and the extension's background `URLSession` — the retrying
caller this was actually for — still needs to be built before it has anything
to send. Phase 4's `alreadyExists` state can now rely on this rather than
needing its own dedupe.

### Mobile

**Share extension spike — the single highest-risk mobile unknown.** Prove out, in this order:
1. `expo-share-extension` receives a URL from Instagram's share sheet
2. Background `URLSession` with `sharedContainerIdentifier` POSTs it and survives extension termination
3. Keychain access group shares the token; **refresh an expired token inside the extension** (this is the part that bites)
4. App Group `UserDefaults` carries the "open app" toggle

Android: no-display Activity + WorkManager. Straightforward — do it second.

**Exit criteria:** 🟡 share a YouTube Short → captions land in `save_stages` with no Gemini call *(the yt-dlp half is proven live — probe, caption fetch and VTT-to-prose all run against a real video; what is unproven is the share hand-off and the `save_stages` write from a device)* · 🟡 share an Instagram Reel with no captions → Whisper transcript lands *(ASR is built and unit-tested — download, downmix and Groq call all mocked — but has never run against a real video or a real Groq API key; also still gated on the share hand-off from a device, same as above)* · ⬜ a killed extension still completes its upload · 🟡 a yt-dlp failure produces a human-readable message *(the mapping table is tested against captured stderr, and one live `HTTP 429` was classified correctly as retryable `source_blocked`; other live failures untested)*.

**Risks:** Instagram needs curl-cffi impersonation for public Reels (confirmed in the teardown). Budget two days. Treat unauthenticated failure as an acceptable outcome, not a blocker.

**Where Phase 2 actually stands:** the backend half is *written*, and only steps 1–2 are *proven* — runner, all four cascade steps, process plumbing and error classification are written; the runner is verified against live Supabase, and captions/metadata are verified against a real yt-dlp. ASR and the link/PDF branch are new as of 2026-07-31 and unit-tested only; a `curl`-driven end-to-end pass the same day confirmed the app boots and processes a save with these classes wired in (it had silently failed to start at all until a missing `spring-boot-starter-restclient` dependency was found and fixed — see the root README), but that pass hit a fake URL and did not exercise ASR or link/PDF against real content. What remains is not backend *code*: Android's silent-capture receiver is built but unrun on a device; the iOS share extension does not exist at all, and ASR/link/PDF have never touched a real network. The next move is the iOS extension spike, which is the single highest-risk mobile unknown and has hard lead times — but a live pass on ASR (a real `WEAVR_GROQ_API_KEY`, a real video with no captions) is worth doing before trusting it, on the strength of the same lesson twice now.

Worth carrying forward: the caption path had 27 green tests and three real defects, and the classify path had 67 and one (the encoding bug) — both times the gap was entirely "the real thing was mocked". The same shape of gap is now open on ASR, link extraction and PDF extraction (46 tests, all mocked), and still open on `JobStore`'s claim query, which is exercised only by one manual run against Supabase.

---

## Phase 3 — AI pipeline v1 (Week 3: Aug 15–21)

**Goal:** text blob in, structured save out, one Gemini call.

### Backend

**Budget layer first, before any Gemini client exists** (CA#1):

```java
public final class BudgetApproved {          // private constructor
    private BudgetApproved(String model, UUID saveId) { ... }
}
// Only GeminiBudgetService can mint one. GeminiClient requires one.
```

The counter lives in Postgres, resets on Google's boundary, and survives deploys. Exhaustion → `RetryAfterException` until the next window, and the save stays `pending` with the user informed — never a hard failure.

**One call per save.** `responseMimeType: application/json` + `responseSchema` with `anyOf` over the v1 type schemas. Returns `knowledge_type` + `confidence` + type fields together. Thinking enabled explicitly (Flash-Lite ships with it effectively off).

Model routing (CLAUDE.md § AI pipeline): clean text → Flash-Lite; low confidence → Flash retry. Pools are per-model, so this *adds* capacity.

Prompt hardening from the teardown: junk guard returning `knowledge_type: unusable` on error pages, blocked downloads, and empty extractions (CA#6) · `[unclear]` uncertainty markers (CA#7) · whitespace collapse before prompting.

Type schemas + few-shot examples live in a **registry keyed by `knowledge_type`**, so adding a type is data, not code.

Also: model fallback chain on 404 (CA#10) · per-stage idempotency flags so a redeploy never re-spends a request (CA#12) · log every call to `gemini_calls`.

### Mobile

Feed screen — `GET /v1/saves`, rendering structured cards per type. Processing states visible. Pull to refresh.

**Done, 2026-07-31.** The feed, its four states (loading / error / empty /
per-status) and pull-to-refresh were already in; React Query was not used,
since a single list with manual refresh did not justify the dependency. The
*type-specific card* — recipe, movie, place layouts driven by `knowledgeType` +
`structuredData`, via `buildCardModel` (`app/src/saves/cardModel.ts`) and
`SaveCard` — is now what the Home feed renders for `ready` saves, falling back
to the flat row for anything still processing, `unusable`, or a type without a
bespoke layout yet. Typechecks and bundles for web; not run on a device, same
as the rest of the app.

**Two Phase-3 hooks already exist in the runner** and were used rather than
rebuilt: `RetryAfterException` is exactly the budget-exhaustion path (reschedules
without spending an attempt, so a day of quota rejections cannot fail a save),
and `save_stages` already holds the extracted text the model call consumes.

**Exit criteria:** a recipe Reel with captions becomes a structured recipe card, one Gemini call, visible in `gemini_calls` ✅ · budget exhaustion queues rather than fails ✅ (unit-tested; not yet observed live against an exhausted pool) · a blocked download yields `unusable`, not invented ingredients 🟡 (verified with a scripted `unusable` response, not yet a real blocked download) · the app shows real cards ✅.

---

## Phase 4 — OCR tier and the honest eval (Week 4: Aug 22–28)

**Goal:** overlay-only Reels work. This is the differentiator and the hardest extraction case.

### Backend

Everything here shells out to ffmpeg and tesseract, so it goes through the same
`ExternalProcess` the cascade uses — already built and hardened against the
hang-not-fail cases. The frame-extraction and OCR commands are new; the process
plumbing is not.

Frames: `select='gt(scene,0.25)',mpdecimate,scale=768:-1` — scene detection plus dedupe so a 5-second ingredient card isn't 5 frames. ⚠️ **Measured wrong — see the 2026-08-01 note below.** That selector yields zero frames on a static card; it needs `+eq(n,0)+not(mod(n,150))`.

OCR: **Tesseract with `tessdata_fast`** as an external process. Preprocess (upscale, grayscale, ~~CLAHE, adaptive threshold~~ — ⚠️ **also measured wrong; a contrast stage makes overlay text worse, not better**). **Vote across duplicate frames** — the cheapest accuracy win available, with no analogue in a one-shot vision call.

Escalation gate: per-word confidence **plus** schema sanity (a recipe needs quantities and units). Below the floor → Flash vision. Confidence alone will happily pass a fluent misread.

Thumbnail: end-weighted sampling + variance-of-Laplacian, **locally** (CA#15). Do not spend a request choosing a thumbnail.

### The eval set — Dev B owns this

**Thirty real Reels**, hand-labelled: overlay-only recipes, workout cards, product shots, captioned videos, a blocked Instagram post, a silent slideshow. Measure Tesseract vs Flash and set the escalation threshold from data.

Without this, the threshold is a guess and both failure directions are invisible: you either burn the Flash pool or ship silent hallucinations that users never report.

### Mobile

**Silent capture end-to-end.** Share → "Saved ✓" auto-dismiss → back in the Reel → push notification when ready. Settings toggle (mirrored to App Group). State machine from the teardown: `idle` / `success` / `alreadyExists` / `error`, auto-dismiss at 2.5 s / 3 s, and the **5-second escape hatch** so nobody is ever trapped in a spinner (CA#11).

The *Open app when saving* toggle already exists in the app and defaults to off,
and `src/prefs/shareExtensionBridge.ts` is a deliberate no-op marking where the
App Group mirror has to be written. `alreadyExists` depends on the save-level
idempotency that landed 2026-07-31 (✅ above) — the extension just needs to
generate an `Idempotency-Key` once per share and send it on every retry;
without that, a re-share still creates a duplicate rather than reporting one.

**2026-08-01: the backend half of this phase landed, and the pattern from Phase
2 repeated with a twist.** `pipeline/ocr/` holds the tier — bounded
worst-quality download, one ffmpeg pass cutting deduped keyframes into a colour
branch and an OCR-prepped grey branch, tesseract per frame in TSV mode for its
per-word confidence, a vote across frames, and the escalation gate. 57 new
tests; suite is 184, green.

**The twist: this time the real binaries were run *during* development, and they
overturned two decisions written into this very document.** Both are recorded in
[testing.md](testing.md#what-running-the-real-binary-found-the-second-time-visual-tier-2026-08-01):

- **The filter chain specified above selects zero frames on a static card** —
  frame 0 has nothing to differ from and nothing after it changes, so scene
  detection never fires on precisely the overlay-only Reel this phase exists
  for. Corrected to take frame 0 and a periodic sample as well, with
  `mpdecimate` still collapsing the duplicates that adds.
- **"Preprocess: upscale, grayscale, CLAHE contrast" is wrong for this input.**
  Overlay text is high-contrast and bimodal by design; a global histogram remap
  hollows the glyphs. Real tesseract, same frame: 16/16 words at confidence 95
  without it, 7 garbled tokens at 22 with it — and 22 is *below* the escalation
  floor, so the recommended preprocessing would have spent a Flash vision
  request repairing its own damage.

Neither was a coding error. The code implemented the plan faithfully; the plan
was wrong, and only the binary knew.

**Two deliberate deviations from the design above.** *Schema sanity* is not in
the pre-model gate — the knowledge type is what the Gemini call *returns*, so
the gate cannot know which schema to check, and buying the type first would
spend the request the gate exists to protect. It is applied for free one stage
later instead, via the model's own `unusable` verdict. And *Tier 2 returns text,
not a classification*: the frames go to Flash vision, a verbatim transcription
comes back, and it flows through the ordinary classify call unchanged. That
costs an escalating save two requests rather than one — accepted, because the
alternative (a multimodal classify call) needs frames to survive from
`process_save` into `classify_save`, which means persisting base64 images into
JSONB or downloading the video twice. The 20-RPD fallback pool caps how many
saves can ever take that path.

**Exit criteria:** ✅ an overlay-only recipe Reel extracts correctly with no Gemini vision call *(against a rendered fixture card, end to end through real ffmpeg and tesseract — not yet against a real Reel)* · ⬜ **eval set measured, threshold set from data, numbers written down — not started, and it is now the tier's biggest risk** · ⬜ sharing never opens the app · ⬜ re-sharing shows "Saved again," not an error.

**Where Phase 4 actually stands:** the backend is done and, unusually for this
repo, live-verified on the same day it was written. What is missing is not code.
Every threshold in the gate is a guess — `min-mean-confidence: 60` was
sanity-checked against a clean synthetic card (95) and a textless clip, never
measured — and everything the tier has read so far is a fixture it generated
itself. Real text over photographs, motion blur and stylised fonts are where
tesseract fails hard rather than gracefully, and that is exactly what the thirty
hand-labelled Reels are for. Dev B owns it, it is product judgement rather than
code, and without it both failure directions stay invisible. The mobile half of
this phase (silent capture end to end) still waits on the iOS extension, which
remains the highest-risk unbuilt thing in the project.

---

## Phase 5 — Make it a product, then ship it (Week 5: Aug 29–Sep 4)

**Goal:** public release. Narrow but complete.

### Backend

- ✅ **Search**: Postgres FTS + pgvector, fused with **RRF** (CA#5, `rank_constant=60`, ignore source scores). Falls back to either half alone. **Done and verified live 2026-08-01** — `GET /v1/saves/search`. Two additions the plan did not anticipate, both forced by the live run: V1's `search_tsv` could not find a `place` by its own name (its field is `name`, not `title`) so V3/V4 rebuild it weighted, and the vector half needed a **distance cutoff** because k-NN has no concept of "no match" — `zzzzqqq` returned the entire library.
- ✅ **Embeddings**: structured `label: value` profile from `structured_data`, not raw text (CA#8). Separate rate-limit pool — embed freely, and the one Gemini call not behind `BudgetApproved`. **Done and verified live.** Watch out: `gemini-embedding-001` truncates rather than re-embeds for reduced dimensions, so a 1536-d vector comes back un-normalised (L2 0.69) — normalise client-side.
- ✅ **One Act, done well**: recipe → shopping list. **Done and verified live 2026-08-01** — one open list per user, ingredients normalised into products/quantities/aisles by a single Gemini call and merged across recipes. Two real recipes gave garlic 7 cloves (3 + 4) and olive oil 4 tbsp (2 + 2). The trap: a merged line must store each contributing recipe's own quantity, not a running total — the accumulate-on-add version silently inflated quantities every time the runner re-delivered a job (7 cloves → 10), and only a real retry against a real queue exposed it.
- ✅ **Lifecycle**: `saved → planned → started → completed`. `PATCH /v1/saves/{id}/lifecycle`, plus a `?lifecycle=` filter on the feed that backs the app's Continue rail. **Deliberately not a state machine** — un-completing something, or dropping a planned recipe back to saved, are both ordinary things to want, and rejecting them would buy nothing but a 400 the user cannot act on.
- ✅ **Free-tier caps** enforced in the worker (20 saves/month, 1 Act/week), checked *before* the budget is acquired so an over-quota save never reaches the model. Enforcement is behind `weavr.billing.enforce-free-caps`, **off by default** — a cap only means something once there is a paid tier to escape to, and the counters run either way so the switch can be flipped against real numbers. Two periods share `usage_counters` by writing at their own `period_start` (month for saves, ISO-week Monday for Acts) and only ever reading their own column.
- ✅ **RevenueCat webhook** → `subscriptions`, gating server-side. **Verified live across six event shapes.** The design decision worth keeping: entitlement is derived from **expiry**, never from the event type. A type-switch — the shape every tutorial shows — revokes on `CANCELLATION`, which means auto-renew off rather than access ended, and so cuts off a user who has paid through the period. Two guards in SQL rather than in code: `on conflict (id) do nothing` for at-least-once delivery, and a `last_event_at <=` clause for out-of-order delivery, which otherwise lets a delayed retry hand a lapsed user a permanent subscription.

### Enrichment — a Phase 3 step that was never built (done 2026-08-01)

- ✅ **TMDB (`movie`) and Google Places (`place`)**, merged into `structured_data`. Costs no Gemini request, which is the whole argument for it on a 500-a-day budget.
- **It runs between classify and embed, not after.** The embedding is built from `structured_data` (CA#8), so embedding first would permanently omit the director, the address and the coordinates — the highest-signal terms a semantic search has to match on. Re-embedding afterwards is not an option: the embed job is deduped by a key derived from the save id, so a second one silently never runs.
- **Additive only.** Fills a missing field or the registry's `[unclear]` sentinel; never overwrites a value the content produced. If a caption says 20 minutes and a database says 25, the caption is what the user saw and chose to save — and it bounds the blast radius of a wrong match to an added field rather than a corrupted one.
- **Every one of these APIs is a nearest-match with no "no result".** TMDB answers a garbled OCR title with *something*; Places answers "Noma" with a coffee roaster. Same failure as the k-NN search returning the whole library for `zzzzqqq`, same fix: a similarity threshold (`TitleMatch`), plus a hard year filter for remakes.

### Mobile

Feed · search · save detail · shopping list · settings · onboarding tuned for the §3 flow (first completed save inside 30 seconds, paywall *after* the first aha). RevenueCat paywall via `react-native-purchases-ui` templates — don't hand-roll.

Feed and settings exist. **Settings is currently an Appearance screen only** —
theme, accent, cover, typeface, nav style, blur, greeting, and the capture toggle
— so the product settings (account, caps, subscription state) still need a home.
**Search and save detail landed 2026-08-01**, alongside the backend half — so
every endpoint the API serves now has a consumer, which had not been true since
Phase 3. Library was rebuilt on real data at the same time (per-type counts and
filter chips derived from the knowledge types actually present; the fictional
"AI groups" grid removed). The shopping list is unbuilt, and depends on the one
Act, which is also unbuilt.

### Ship

Store listings, screenshots, privacy policy (**including the Gemini free-tier data-use disclosure**, and Apple's App Privacy + Play Data Safety forms answered against what the free tier actually does). Sandbox and license-tester purchases verified. Submit for review Sep 1–2.

**Exit criteria:** public on at least one store · a real purchase completes in sandbox · entitlement gating verified server-side · first #BuildInPublic post.

---

## Phase 6 — Spaces (Week 6: Sep 5–11)

**Goal:** the growth loop from §3 — free users get pulled into a Pro user's Space.

- ✅ Spaces CRUD, invite by code (the client builds the link and the QR from it), `owner|editor|viewer` **role enum** — not Linkwarden's capability booleans, which the teardown flagged as a do-not-adopt because every new capability becomes another column and most combinations are nonsense nobody intends to grant.
- ⬜ Realtime sync for shared Spaces. Not built; the screens refetch on focus and pull-to-refresh.
- ✅ **Embedding-similarity dedupe on save into a shared Space.** Writes a *suggestion* to `save_duplicates`, never a merge — cosine distance is evidence, not proof, and silently merging someone else's save is not recoverable from the client. "Merge" moves the newer save out of the Space and back to its owner's library rather than deleting it. **Threshold 0.15 is a guess**, argued from the search half's measurements (real query hits at 0.27–0.37; two saves of the *same thing* must be much closer) and never measured against real duplicate pairs.
- ✅ Activity feed: meaningful events only — created, joined, added, completed, commented, voted. Never a per-scroll signal, both because a feed that logs everything is noise and because this is the fastest-growing table on a 500 MB free tier.
- ✅ Comments and voting. **Votes are a row per (save, user), not a tally on the save** — the shopping list's accumulate-on-add bug applied before it could bite: a stored total is correct exactly once and drifts on any replay, where a `sum` over rows depends only on who voted.

**Two things the live pass established that the design above did not say.**
First, **404 and 403 mean different things here**: a non-member gets 404, because the existence of a Space they were never invited to is not theirs to learn, while a member with too low a role gets 403, because they already know it exists and hiding it would only confuse them. Second, **`POST /v1/saves` had been writing `spaceId` straight from the request body since Phase 1 with nothing checking it** — any authenticated user could drop a save into any Space whose id they had once been shown. Closed, and pinned by a test.

**Exit criteria:** ⬜ two devices, one Space, live sync *(needs devices, and Realtime is unbuilt)* · 🟡 two people saving the same restaurant from different URLs get a merge suggestion *(built; never run against two real saves, so the threshold is untested)* · ✅ a free user can use an invited Space fully *(a viewer reads, comments and votes — verified live; only adding content needs editor)*.

---

## Phase 7 — Widen on evidence (Week 7: Sep 12–18)

Now you have usage data. Let it choose.

- Knowledge types 5–12, driven by what users actually save (registry makes this data, not code)
- Acts for the top three types by volume
- AI Weekly Digest — **staggered**, or it eats a day's save capacity
- Project Builder v1 on the largest observed cluster
- Paywall placement and copy iteration on real conversion numbers

**Exit criteria:** type coverage matches observed usage · digest ships without starving saves · paywall iterated on data, not opinion.

---

## Phase 8 — Submit and push (Week 8: Sep 19–30)

- Devpost submission: description, ≤3 min demo video (hook in the first 10 seconds — judges watch dozens), store links
- #BuildInPublic thread linked, with evidence of acting on feedback (this is scored)
- Bug bash against real usage
- **Keep shipping through Sep 30** — traction during the window is the Grand Prize criterion

---

## Standing rules

**Weekly:** Monday — pick the phase's riskiest unknown and spike it first. Friday — demo on a physical device; if it can't be demoed it isn't done. Post publicly.

**Never:** ship a Gemini call that isn't behind `BudgetApproved` · let a quota rejection consume retries · re-spend a request on a retry that a stage cache could serve · block the share sheet on a network call.

**Watch weekly:** RPD consumed vs cap · Flash escalation rate · extraction success by source (Instagram will be worst) · saves→first-Act conversion.

## Cut list, in order

If Week 5 slips, cut in this order — the first four are safe, the last one is not:

1. Project Builder → Phase 7
2. Weekly Digest → Phase 7
3. Comments and voting → post-submission
4. Knowledge types beyond four → Phase 7
5. **Spaces → Phase 6** (already there; do not cut further — it's the growth loop)

Never cut: silent capture, the budget token, or the eval set. Those are the product, the constraint, and the quality floor.
