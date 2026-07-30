# Implementation Plan

Combined backend + mobile plan for the Shipaton window (Aug 1 – Sep 30, 2026). Two developers.

Read alongside [CLAUDE.md](../CLAUDE.md) (architecture and constraints) and [competitive-analysis.md](competitive-analysis.md) (the ranked steal list, referenced below as **CA#n**).

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

**Done ahead of this:** the UI shell (Home / Library / Spaces / Capture) and the whole theme and personalisation layer are built and bundle clean — see [app/README.md](../app/README.md). What is left of Phase 1 mobile is exactly the part above: Supabase client, auth, and the API call. The Capture sheet's tiles are the natural place to hang the first `POST /v1/saves`.

### Parallel

App Store Connect app record. Play Console app record. RevenueCat project created and linked to both.

**Exit criteria:** `curl` with a real Supabase JWT creates a save · dev client runs on a physical device and creates a save · Flyway migrates cleanly against Supabase · both store records exist.

**Risks:** Apple enrolment still pending — if so, do Android first and keep iOS moving on the simulator.

---

## Phase 2 — Ingestion cascade, no AI (Week 2: Aug 8–14)

**Goal:** any shared URL produces a text blob. Zero Gemini calls.

### Backend

**Job runner first** — `SELECT ... FOR UPDATE SKIP LOCKED`, honouring `priority`, `run_after`, and `group_id` round-robin (CA#3). Concurrency capped at **1–2**. This is the backbone; everything else is a job type.

Two error paths, distinguished from the start (CA#2):
- `RetryableException` → increments `attempts`
- `RetryAfterException(delayMs)` → sets `run_after`, **does not** increment `attempts`

The second one is what keeps a Gemini quota rejection from burning the retry budget in Phase 3.

**The cascade** (CLAUDE.md § text-extraction cascade), in order:
1. `yt-dlp --skip-download --write-auto-subs --write-subs` → captions
2. `--write-info-json` → title, description, hashtags, uploader
3. ASR fallback: `ffmpeg` → 16 kHz mono → Groq Whisper
4. Readable-text extraction for links; text extraction for PDFs

Process hygiene, non-negotiable (these hang rather than fail):
- Drain stdout **and** stderr on separate threads
- `waitFor(timeout)` + `destroyForcibly()`
- `-f 'worst[height>=360]'`, `--download-sections "*0-90"`, pipe to ffmpeg, delete in `finally`

Steal from the teardown: caption-language discovery from `subtitles` + `automatic_captions` (CA#14) · `ffprobe` audio-stream probe before ASR (CA#13) · yt-dlp error → user-message mapping table (CA#9) · write each stage to `save_stages` (CA#4).

### Mobile

**Share extension spike — the single highest-risk mobile unknown.** Prove out, in this order:
1. `expo-share-extension` receives a URL from Instagram's share sheet
2. Background `URLSession` with `sharedContainerIdentifier` POSTs it and survives extension termination
3. Keychain access group shares the token; **refresh an expired token inside the extension** (this is the part that bites)
4. App Group `UserDefaults` carries the "open app" toggle

Android: no-display Activity + WorkManager. Straightforward — do it second.

**Exit criteria:** share a YouTube Short → captions land in `save_stages` with no Gemini call · share an Instagram Reel with no captions → Whisper transcript lands · a killed extension still completes its upload · a yt-dlp failure produces a human-readable message.

**Risks:** Instagram needs curl-cffi impersonation for public Reels (confirmed in the teardown). Budget two days. Treat unauthenticated failure as an acceptable outcome, not a blocker.

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

Feed screen — `GET /v1/saves` with React Query, rendering structured cards per type. Processing states visible. Pull to refresh.

**Exit criteria:** a recipe Reel with captions becomes a structured recipe card, one Gemini call, visible in `gemini_calls` · budget exhaustion queues rather than fails · a blocked download yields `unusable`, not invented ingredients · the app shows real cards.

---

## Phase 4 — OCR tier and the honest eval (Week 4: Aug 22–28)

**Goal:** overlay-only Reels work. This is the differentiator and the hardest extraction case.

### Backend

Frames: `select='gt(scene,0.25)',mpdecimate,scale=768:-1` — scene detection plus dedupe so a 5-second ingredient card isn't 5 frames.

OCR: **Tesseract with `tessdata_fast`** as an external process. Preprocess (upscale, grayscale, CLAHE, adaptive threshold) — this matters more the lighter the engine. **Vote across duplicate frames** — the cheapest accuracy win available, with no analogue in a one-shot vision call.

Escalation gate: per-word confidence **plus** schema sanity (a recipe needs quantities and units). Below the floor → Flash vision. Confidence alone will happily pass a fluent misread.

Thumbnail: end-weighted sampling + variance-of-Laplacian, **locally** (CA#15). Do not spend a request choosing a thumbnail.

### The eval set — Dev B owns this

**Thirty real Reels**, hand-labelled: overlay-only recipes, workout cards, product shots, captioned videos, a blocked Instagram post, a silent slideshow. Measure Tesseract vs Flash and set the escalation threshold from data.

Without this, the threshold is a guess and both failure directions are invisible: you either burn the Flash pool or ship silent hallucinations that users never report.

### Mobile

**Silent capture end-to-end.** Share → "Saved ✓" auto-dismiss → back in the Reel → push notification when ready. Settings toggle (mirrored to App Group). State machine from the teardown: `idle` / `success` / `alreadyExists` / `error`, auto-dismiss at 2.5 s / 3 s, and the **5-second escape hatch** so nobody is ever trapped in a spinner (CA#11).

**Exit criteria:** an overlay-only recipe Reel extracts correctly with no Gemini vision call · eval set measured, threshold set from data, numbers written down · sharing never opens the app · re-sharing shows "Saved again," not an error.

---

## Phase 5 — Make it a product, then ship it (Week 5: Aug 29–Sep 4)

**Goal:** public release. Narrow but complete.

### Backend

- **Search**: Postgres FTS + pgvector, fused with **RRF** (CA#5, `rank_constant=60`, ignore source scores). Fall back to FTS when the query has no free-text terms.
- **Embeddings**: structured `label: value` profile from `structured_data`, not raw text (CA#8). Separate rate-limit pool — embed freely.
- **One Act, done well**: recipe → shopping list. Depth beats breadth here.
- **Lifecycle**: `saved → planned → started → completed`.
- **Free-tier caps** enforced in the worker (20 saves/month, 1 Act/week).
- **RevenueCat webhook** → `subscriptions`, gating server-side.

### Mobile

Feed · search · save detail · shopping list · settings · onboarding tuned for the §3 flow (first completed save inside 30 seconds, paywall *after* the first aha). RevenueCat paywall via `react-native-purchases-ui` templates — don't hand-roll.

### Ship

Store listings, screenshots, privacy policy (**including the Gemini free-tier data-use disclosure**, and Apple's App Privacy + Play Data Safety forms answered against what the free tier actually does). Sandbox and license-tester purchases verified. Submit for review Sep 1–2.

**Exit criteria:** public on at least one store · a real purchase completes in sandbox · entitlement gating verified server-side · first #BuildInPublic post.

---

## Phase 6 — Spaces (Week 6: Sep 5–11)

**Goal:** the growth loop from §3 — free users get pulled into a Pro user's Space.

- Spaces CRUD, invite by link + QR, `owner|editor|viewer` **role enum** (not Linkwarden's capability booleans)
- Realtime sync for shared Spaces
- **Embedding-similarity dedupe on save into a shared Space** — no competitor does better than exact-URL matching; this is genuinely differentiating
- Activity feed: meaningful events only (completed, rated, visited)
- Comments and voting

**Exit criteria:** two devices, one Space, live sync · two people saving the same restaurant from different URLs get a merge suggestion · a free user can use an invited Space fully.

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
