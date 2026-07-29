# Competitive Analysis — Open Source Teardown

Source-level review of five repositories, cloned and read as implementation rather than documentation. Framing: what can a two-person team with a ~500 RPD Gemini budget steal, and what is inherited debt.

**Licensing posture:** Karakeep and Linkwarden are AGPL-3.0. Everything below is architectural observation. No code is copied; techniques are re-implemented from first principles in Java/Spring. `pick-a-recipe`, `sous-clip`, and `recipe-extractor` were read for pipeline shape only.

---

## Verdict up front

| Repo | Size | What it actually is | Value to us |
|---|---|---|---|
| **karakeep** | ~1,960 files, pnpm monorepo | The most engineered of the five. Real queue abstraction, RRF hybrid search, embeddings worker, 11 workers, mobile app | **Highest** — steal the engineering patterns, none of the architecture |
| **linkwarden** | ~693 files | Bookmark manager with collaboration. Simpler than Karakeep | Medium — the permission model is a **cautionary tale** |
| **pick-a-recipe** | 62 files, Flask | The closest analogue to our ingestion pipeline. Genuinely the blueprint | **Highest** for pipeline shape; its AI cost model is the opposite of ours |
| **sous-clip** | 182 files, FastAPI | Same job, enterprise stack (Temporal, Redis, OTel) | Low — mostly an example of over-engineering at our scale |
| **recipe-extractor** | 15 files | ~150 lines that do the core job | High signal-to-noise; validates our cascade |

**The single most important finding:** none of these five products do anything with the content after storing it. All five stop at "structured object saved." Our Act layer, cross-object relationships, and Spaces are genuinely unoccupied ground — the competitive risk is execution, not differentiation.

**The second most important finding:** every one of these assumes an unmetered LLM budget. `pick-a-recipe` spends **three separate LLM calls per video** (visual text extraction, best-frame selection, recipe generation) and uploads the entire video file to Gemini. At 500 RPD that design caps us at ~166 saves/day. Our one-call design is not a micro-optimization; it is the difference between a viable product and a dead one.

---

## 1. karakeep — the engineering reference

### Architecture

pnpm monorepo, Turbo. `apps/{web,workers,mobile,browser-extension,landing}`, `packages/{db,shared,trpc,plugins,...}`. Next.js + tRPC + Drizzle + SQLite. Workers are a separate deployable process consuming a queue.

Eleven distinct workers (`crawler`, `embeddings`, `assetPreprocessing`, `video`, `search`, `feed`, `import`, `webhook`, `ruleEngine`, `backup`, `adminMaintenance`). Each is an independent queue consumer with its own concurrency and timeout. **This is the correct shape and we should copy it conceptually** — but as async job *types* within one Spring service, not as separate deployables.

Everything is behind a `PluginManager` abstraction: queue, search, vector store, and rate limiting are all swappable plugin interfaces. **Do not copy this.** It exists because Karakeep must support SQLite-or-Postgres and Meilisearch-or-nothing for self-hosters. We have exactly one deployment target. The abstraction would cost us weeks and buy nothing.

### Hidden engineering decisions worth stealing

**`QueueRetryAfterError`** (`packages/shared/queueing.ts`) — a dedicated error type carrying `delayMs`, whose contract is: *retry after this delay without counting against the retry limit*. The comment says it exists for rate limiting.

This is precisely our Gemini 429/RPD problem. A daily-quota rejection is not a job failure and must not consume the retry budget, or a quota blip permanently kills saves. **Adopt immediately** — it's one exception class and one branch in the worker loop.

**`EnqueueOptions: { idempotencyKey, priority, delayMs, groupId }`** — four fields, each solving a real problem:
- `idempotencyKey` — double-taps on the share sheet don't create two saves.
- `priority` — interactive saves jump ahead of digests and backfills.
- `groupId` — **this is per-user fairness.** It directly solves the "one user's burst starves everyone" risk in our RPD budget.

**Adopt immediately.** All four are columns on our jobs table; `groupId` becomes a round-robin term in the `SKIP LOCKED` claim query.

**`QuotaApproved` capability token** (`packages/shared/storageQuota.ts`) — a class with a **private constructor**, creatable only by the quota checker, and required as a parameter by the upload path. The type system enforces that the quota check happened. You cannot forget it, because the code won't compile.

This is the cheapest high-value idea in the entire teardown, and it maps perfectly onto our RPD budget: a `BudgetApproved` object that only `GeminiBudgetService` can mint, required by every Gemini client method. **Adopt immediately.** Perhaps 30 lines in Java.

**Reciprocal Rank Fusion** (`packages/trpc/lib/searchRanking.ts`) — how they fuse full-text and vector results. The comment is the lesson:

> RRF deliberately ignores source scores because full-text and vector search scores do not share a meaningful scale.

Score: `Σ 1/(60 + rank)` across sources, ties broken by best rank. ~25 lines, no tuning, no normalization. This is exactly the right answer to hybrid search and it kills the temptation to hand-weight BM25 against cosine similarity. **Adopt immediately.**

They also fall back to pure FTS when the query has no free-text terms or embeddings are unavailable, and drop semantic hits below a similarity floor. Both are cheap correctness wins.

**Structured embedding profile** (`embeddingsWorker.ts`) — they do not embed raw content. They build a labeled `"field: value; field: value"` profile (title, hostname, stripped URL, flattened metadata, date-only timestamps, budgeted content excerpt) with per-section length caps and ellipsis truncation. URLs are normalized by stripping query and hash.

This is directly applicable: our `structured_data` JSONB flattens naturally into the same shape, and it makes embeddings comparable across knowledge types. **Adopt immediately.**

**Prompt hardening against junk** (`packages/shared/prompts.ts`) — the tagging prompts spend more lines on what *not* to do than what to do. Explicit instruction to return an **empty array** for error pages, Cloudflare/CAPTCHA interstitials, login walls, cookie banners, and blank content.

This is a scar from production. Crawled content is frequently garbage, and a model asked to tag garbage will invent tags. Our equivalent: OCR output from a failed extraction, or a yt-dlp metadata blob from a blocked download. **Adopt immediately** — an explicit "if the input is unusable, return `knowledge_type: unusable` and empty fields" branch in our schema is the guard against confidently-wrong saves.

**`[unclear]` uncertainty marker** (`buildOCRPrompt`) — their LLM-as-OCR prompt instructs the model to mark uncertain text rather than guess. This is a direct mitigation for the hallucination risk in our light-OCR strategy: garbled input plus an instruction to flag uncertainty is far safer than garbled input alone. **Adopt immediately.**

**`preprocessContent`** — collapses runs of 10+ whitespace chars before prompting. Trivial, and pure token savings on scraped text.

### Search

Three modes: `fts`, `semantic`, `hybrid`. FTS via Meilisearch (or SQLite FTS), vectors in a separate pluggable vector store, fused with RRF at the tRPC layer.

**Adopt the model, reject the infrastructure.** Two external services is the wrong trade for us — Postgres FTS plus pgvector in one database gives us the same two ranked lists to fuse, in one query path, with no extra deployable and no sync problem between stores. Karakeep needs the abstraction because self-hosters won't all run Meilisearch. We don't.

### Deduplication — a gap, not a lesson

`attemptToDedupLink(ctx, input.url)` dedupes on **exact URL only**. That's it. There is no fuzzy matching and no embedding-similarity dedupe anywhere in the codebase.

This is a genuine gap we can exploit. Two people sharing the same restaurant from Instagram and Google Maps produce different URLs and will silently duplicate in a shared Space. Our embedding-similarity dedupe (spec §2.5) is a real differentiator, not a nice-to-have.

### Mobile share UX

`apps/mobile/app/sharing.tsx` uses `expo-share-intent` — the app opens to a modal. This is the model we rejected. But the **state machine inside it is worth copying wholesale** into our silent share extension:

- Four states: `idle` / `success` / `alreadyExists` / `error`.
- **`alreadyExists` is a distinct friendly state** ("Hoarded again!"), not an error. Re-saving something is a normal thing users do and must not look like a failure.
- Auto-dismiss timers: 2.5 s on success, 3 s on error.
- **A 5-second "idle escape hatch"** — if still saving after 5 s, reveal a Dismiss button. The comment explains why: an empty share intent leaves nothing to mutate, and an unreachable server hangs indefinitely. The user is never trapped in a spinner.

That escape hatch is the kind of defensive detail you only write after a bug report. **Adopt immediately** — it costs one `setTimeout`.

---

## 2. linkwarden — mostly a cautionary tale

### The permission model — do not copy

```prisma
model UsersAndCollections {
  userId       Int
  collectionId Int
  canCreate    Boolean
  canUpdate    Boolean
  canDelete    Boolean
  @@id([userId, collectionId])
}
```

Three independent capability booleans on the membership junction. That is 8 possible combinations, of which perhaps 3 are meaningful, and none have names. Every authorization site must check the right boolean, every UI must render an unnamed permission set, and "what can this person do?" has no simple answer.

**Our spec's `owner | editor | viewer` enum is strictly better** at our scale and team size. Capability flags are the right design at enterprise granularity; they are debt at ours. Keep the role enum, and if a role ever needs splitting, split the enum.

The junction-table *shape* is right, though — composite PK on `(userId, collectionId)` plus a secondary index on `userId`. Our `space_members` should look the same.

### Worth stealing

**`aiTagged Boolean @default(false)`** on `Link` — a persisted idempotency marker so AI tagging never re-runs on redeploy or retry. Ours is more valuable still, because a re-run costs a scarce Gemini request, not just money. Our `saves` table wants the same flag per AI stage. **Adopt immediately.**

**Lifecycle timestamps** — `lastPreserved`, `importDate`, `createdAt`, `updatedAt` as distinct fields. Cheap, and they make "what's stale?" and "what came from an import?" queryable without a separate audit table.

**Single index on `collectionId`** for `Link` — a reminder that a bookmark app's hot query is "items in this container, newest first." Ours is the same: `(space_id, created_at DESC)` and `(user_id, created_at DESC)`.

---

## 3. pick-a-recipe — our pipeline blueprint

Flask + SQLite + faster-whisper + yt-dlp. 62 files. This is the repo the spec called the blueprint, and that assessment holds — with one large caveat about cost.

### The pipeline (`pipeline.py`)

Linear, staged, with a progress reporter and cancellation checks between every stage:

```
info → download → transcribe (audio) → visual text → image candidates → LLM recipe → upload
```

**Stage-level file caching keyed by video ID** is the standout technique. Each stage writes its output to `/tmp/<video_id>/`:

- `transcription_{lang}.txt`
- `visual_{lang}.txt`
- `dish.jpg` + `dish_frames/`

Every stage checks for its cache file first. A retried or resumed job skips completed work — including the expensive transcription — and pays only for what's missing.

**Adopt immediately, adapted.** Our version writes stage outputs to a `save_stages` table (or Supabase Storage) keyed by save ID. The payoff is larger for us than for them: a failed enrichment must never force a re-spend of a Gemini request on re-classification.

**The combined-context prompt validates our single-call design:**

```
=== AUDIO TRANSCRIPTION ===
{transcription}

=== ON-SCREEN TEXT (ingredients, instructions, etc.) ===
{visual_text}
```

One labeled text blob, both modalities, one extraction call. This is exactly the assembly step we specified. The section headers matter — they let the model weight sources against each other when they disagree.

**Cancellation checks between every stage** — `if reporter.is_cancelled(): return`. Cheap, and it prevents burning an LLM call for a job the user abandoned. Directly relevant given our budget.

### `llm_resilience.py` — model deprecation self-healing

A dedicated module whose docstring names the production incident that caused it: a hardcoded `gemini-2.0-flash` started 404ing after Google retired it, taking the whole app down.

The design: per-provider fallback chains, the configured model tried first, provider-agnostic detection of "model is gone" via HTTP 404 plus a list of message markers (`model_not_found`, `deprecated`, `no longer available`, …), and — the clever part — **on success with a fallback model, persist that model back to config** so the dead model is never called again. Genuine errors (bad key, network, content) are re-raised immediately rather than churning the whole chain.

Given how fast Google moves model IDs, and that we've already seen `gemini-2.5-flash` limits change under us, **adopt immediately**. Our variant is simpler: an ordered model list in config, fallback on 404/NOT_FOUND only, and a log line loud enough to notice. Skip the config self-write — for us it hides a change we want to see.

### `video_downloader.py` — error message mapping

`format_download_error()` translates raw yt-dlp exceptions into actionable user-facing messages: Instagram blocking → "needs browser impersonation (curl-cffi); private posts also need cookies"; YouTube bot check → "upload a cookies.txt"; missing impersonation target → the exact pip command.

**This directly answers our open decision #11 (failure UX).** Silent capture means nobody is watching when yt-dlp fails, so the notification we send is the entire failure experience. A mapping table from error signature to user-readable cause is the difference between "Save failed" and "Instagram blocked this — try again from the app." **Adopt immediately**, with the caveat that our messages must be user-facing, not operator-facing.

Also worth noting: they need **curl-cffi browser impersonation** for public Instagram Reels. That is a concrete confirmation of the datacenter-IP risk already in CLAUDE.md — plan for Instagram to be the hardest source.

### `transcriber.py` — one great idea, one to reject

**`_has_audio_stream()`** — an `ffprobe` check for any audio stream before attempting extraction, with graceful "continue with visual text only" handling. Silent slideshow Reels are common. Cheap guard, avoids a pointless ffmpeg failure. **Adopt.**

**Audio extraction to 16 kHz mono WAV** — the same normalization already in our plan, independently arrived at. Good confirmation.

**`_extract_visual_text_gemini()` — reject.** They upload the **entire video file** to the Gemini Files API, poll for processing, then prompt over it. This is the single most expensive possible way to read on-screen text and precisely what our OCR-first cascade exists to avoid. Their OpenAI path (8 evenly-spaced frames as images) is closer to ours but still sends every frame to a model.

The comparison is stark: their visual-text step alone is 1 request plus a full video upload; ours is local OCR plus zero requests in the common case.

### `image_extractor.py` — good heuristic, bad spend

**The heuristic is excellent:** frames are sampled **weighted toward the end** of the video (⅓ from the first two-thirds, ⅔ from the final third) because cooking videos show the finished dish last. Mild `unsharp` sharpening on the chosen frame, with a plain copy as fallback.

This generalizes: the payoff shot is near the end for recipes, but overlay *instructions* are spread throughout. Our keyframe selection should be knowledge-type aware — end-weighted for the thumbnail, scene-change-distributed for text.

**The spend is wrong:** they burn a whole LLM vision call to pick the prettiest frame. At our budget that is indefensible for a thumbnail. Use a local heuristic — last-third + sharpness (variance of Laplacian) + reject frames with heavy text coverage, which we already compute during OCR. **Adopt the heuristic, reject the LLM call.**

The user-facing half is worth keeping: they surface all candidates and let the user override the auto-pick, with a 5-minute confirm-before-upload window. A "wrong thumbnail? pick another" affordance is cheap and makes a heuristic acceptable where it would otherwise look broken.

---

## 4. recipe-extractor — 150 lines that validate the cascade

Fifteen files. `video_transcripts.py` is the whole idea and it is the cleanest statement of our cascade in any of the five:

```python
if is_youtube_url(url):
    transcript = get_youtube_transcript(...)   # free captions, zero cost
if not transcript:
    download_audio_with_ytdlp(url)             # audio only, not video
    transcript = transcribe_whisper(...)       # ASR fallback
combined = post_text + "\n\n" + transcript
os.remove(AUDIO_FILE)                          # delete immediately
```

Free captions first, ASR only on miss, audio-only download, metadata always included, media deleted right after. That is our documented cascade, arrived at independently by the smallest codebase in the set. Strong signal we have the shape right.

Two specific techniques:

**`get_caption_languages(info)`** reads both `subtitles` **and** `automatic_captions` from the yt-dlp info dict, plus the declared video language, to build an ordered language preference list. We should do the same rather than assuming English — a Hindi recipe Reel with auto-captions is a free extraction we'd otherwise miss.

**`get_post_text()` tries `description`, then `caption`, then `summary`** — different extractors populate different fields for the same concept. A small normalization layer over yt-dlp's inconsistent metadata keys is worth writing once.

**Verdict:** nothing to adopt architecturally; everything to confirm. This is the "you are not missing anything clever" data point, which has real value.

---

## 5. sous-clip — what over-engineering looks like at our scale

FastAPI + SQLModel + **Temporal** + Redis + SSE + full OpenTelemetry (API, SDK, FastAPI instrumentation, OTLP gRPC exporter), Anthropic *and* OpenAI clients, faster-whisper, `workflows/` and `services/` layers, 42 test files.

Temporal is a durable workflow engine — genuinely excellent technology, and completely wrong for two people with no budget. It's another deployable, another failure domain, and its durability guarantees are ones a `SKIP LOCKED` Postgres queue with an idempotency key gives us at a fraction of the cost.

**Two things worth taking:**

**SSE for job progress** (`sse-starlette`). For "your save is processing" updates, server-sent events are meaningfully lighter than WebSockets — one-directional, plain HTTP, reconnects natively, trivial behind a proxy. Spring supports it directly via `SseEmitter`. Our silent-capture flow means push notification is the primary signal, but SSE is right for the in-app "processing" view. **Adopt later**, when the feed exists.

**The layered split** (`routes/` → `services/` → `workflows/`) is a sane Java-shaped structure that translates directly to `controller/` → `service/` → `pipeline/`.

**Verdict:** read it to confirm which enterprise patterns to skip. The presence of OTel and Temporal in a self-hosted recipe extractor is a useful reminder that "engineered" and "appropriate" are different axes.

---

## Cross-cutting: the steal list, ranked

Ranked by (value ÷ complexity) for our exact constraints.

### Adopt immediately — before MVP

| # | Technique | Source | Why now | Complexity |
|---|---|---|---|---|
| 1 | **`BudgetApproved` capability token** | karakeep `storageQuota.ts` | Compiler-enforced RPD check. Impossible to forget. Our scarcest resource. | ~30 lines |
| 2 | **`QueueRetryAfterError(delayMs)`** | karakeep `queueing.ts` | Quota rejection must not consume the retry budget | 1 class + 1 branch |
| 3 | **`idempotencyKey` + `priority` + `groupId` on jobs** | karakeep `queueing.ts` | Double-tap dedupe, interactive-over-batch, per-user fairness | 3 columns + claim query |
| 4 | **Stage-level caching keyed by save ID** | pick-a-recipe `pipeline.py` | A retry must never re-spend a Gemini request | 1 table |
| 5 | **RRF hybrid search** | karakeep `searchRanking.ts` | Correct fusion, zero tuning, kills score-normalization rabbit hole | ~25 lines |
| 6 | **Junk-content guard in the schema** | karakeep `prompts.ts` | `knowledge_type: unusable` beats a confidently-wrong save | Prompt + enum value |
| 7 | **`[unclear]` uncertainty marker** | karakeep `buildOCRPrompt` | Direct mitigation for light-OCR hallucination risk | 1 prompt line |
| 8 | **Structured `label: value` embedding profile** | karakeep `embeddingsWorker.ts` | Comparable embeddings across knowledge types | ~40 lines |
| 9 | **yt-dlp error → user message mapping** | pick-a-recipe `video_downloader.py` | Answers open decision #11; silent capture makes this the whole failure UX | Lookup table |
| 10 | **Model fallback chain on 404** | pick-a-recipe `llm_resilience.py` | Google retires model IDs; we've already seen limits move | ~40 lines |
| 11 | **Share state machine + 5 s escape hatch** | karakeep `sharing.tsx` | `alreadyExists` as friendly state; never trap the user in a spinner | ~1 hour |
| 12 | **`aiTagged`-style per-stage idempotency flags** | linkwarden `schema.prisma` | Redeploys must not re-spend requests | Columns |
| 13 | **`_has_audio_stream()` probe** | pick-a-recipe `transcriber.py` | Silent Reels are common; skip pointless ffmpeg work | 1 ffprobe call |
| 14 | **Caption-language discovery** | recipe-extractor `video_transcripts.py` | Non-English auto-captions are free extractions we'd otherwise miss | ~15 lines |
| 15 | **End-weighted frame sampling** | pick-a-recipe `image_extractor.py` | Correct heuristic for thumbnails — but local, not LLM | ~20 lines |

### Adopt later — after PMF

- **SSE progress streaming** (sous-clip) — once the in-app processing view matters more than the push notification.
- **Rule engine** (karakeep `ruleEngineWorker`) — user-defined "if type = recipe then add to Space X". Natural fit with our Act layer, but not before we know which actions people use.
- **Feed/import workers** (karakeep) — bulk import from Pocket/Instagram exports. A growth lever, not an MVP one.
- **Webhook worker** (karakeep) — outbound integrations. Only once someone asks.
- **Candidate-image override UI** (pick-a-recipe) — makes a heuristic thumbnail acceptable; low priority until thumbnails visibly misfire.

### Do not adopt

| Thing | Source | Why not |
|---|---|---|
| **Plugin abstraction layer** | karakeep | Exists for self-hosters with different backends. We have one deployment target. Weeks of cost, zero benefit. |
| **Separate search + vector services** | karakeep | Postgres FTS + pgvector gives the same two ranked lists with no extra deployable and no sync problem. |
| **Temporal workflow engine** | sous-clip | Durable execution we get from `SKIP LOCKED` + idempotency keys at a fraction of the operational cost. |
| **Full OpenTelemetry stack** | sous-clip | A `gemini_calls` table answers our only real observability question: what consumed the budget. |
| **Capability booleans for permissions** | linkwarden | 8 unnamed combinations. Our `owner/editor/viewer` enum is strictly better at this size. |
| **Whole-video upload to Gemini** | pick-a-recipe | The exact cost model our cascade exists to avoid. |
| **LLM call to select a thumbnail** | pick-a-recipe | A scarce request spent on a cosmetic choice. Use variance-of-Laplacian locally. |
| **Multi-provider LLM abstraction** | pick-a-recipe, sous-clip | Both carry OpenAI *and* Anthropic/Gemini clients. Premature. One provider, one interface, swap if forced. |
| **Separate worker deployable** | karakeep, linkwarden | Correct at scale, wrong for a free-tier instance. Async pool inside Spring Boot. |

---

## Missing opportunities — unoccupied ground

Read across all five, these are the gaps:

1. **Nothing acts on the content.** All five stop at storage and retrieval. The entire Act layer (shopping list, cook mode, itinerary, watchlist) is uncontested.
2. **No cross-object relationships.** Every item is an island. Nobody links "this recipe uses the pan from that product save." Our Project Builder is genuinely novel here.
3. **Dedupe is URL-exact everywhere.** Karakeep's is the most sophisticated and it is a string comparison. Embedding-similarity dedupe across a shared Space is a real differentiator, especially for places and products where the same entity arrives via different URLs.
4. **No multi-tenant fairness anywhere.** All five are single-user self-hosted. Karakeep's `groupId` is the only primitive that would support it, and it's unused for this. Our free-tier caps and per-user queue fairness have no prior art to copy — design them deliberately.
5. **No collaborative AI.** Linkwarden has shared collections; nothing does shared *understanding* (merge suggestions, group voting informed by extracted attributes).
6. **Nobody solves the metered-budget problem.** Every one assumes unlimited LLM calls. Our constraint is real, and the architecture it forces (one call, local OCR, aggressive caching) is defensible engineering rather than a compromise.
7. **No offline-first client.** All five are thin clients over a server. Given silent capture, a local-first queue that survives airplane mode is a differentiator — though React Query persistence covers most of it cheaply.

---

## Build vs borrow

| Feature | Decision | Reasoning |
|---|---|---|
| Job queue | **Build** (Postgres `SKIP LOCKED`) | Karakeep's interface is right, its plugin layer is not. ~200 lines. |
| Retry/backoff semantics | **Adapt** (karakeep) | Copy the `QueueRetryAfterError` concept exactly; skip the abstraction. |
| Hybrid search | **Adapt** (karakeep RRF) | Algorithm is 25 lines and provably correct. Infrastructure is not ours. |
| Text-extraction cascade | **Build** | Validated by three repos independently; none matches our OCR-first constraint. |
| OCR | **Build** (Tesseract → RapidOCR) | Every repo here uses an LLM for OCR. Our budget forbids it. |
| Prompt structure | **Adapt** (karakeep + pick-a-recipe) | Junk guards, uncertainty markers, labeled multi-source blob. |
| Model fallback | **Adapt** (pick-a-recipe) | Simplify: no config self-write. |
| Permissions | **Build** (role enum) | Linkwarden's model is a worked example of what to avoid. |
| Dedupe | **Build** | No prior art beyond exact-URL matching. Our differentiator. |
| Share extension | **Build** | All use `expo-share-intent`; our silent-capture requirement rules it out. Borrow the state machine only. |
| Enrichment APIs | **Build** | None of them enrich. Straightforward HTTP clients. |
| Act layer | **Build** | No prior art at all. |
| Observability | **Build** (one table) | Skip OTel until there's something to trace. |

---

## Prioritized takeaways

### Must implement before MVP

1. **`BudgetApproved` token + persisted daily counter** — the compiler enforces our scarcest constraint (#1).
2. **Jobs table with `idempotencyKey`, `priority`, `groupId`, and retry-after semantics** — items #2, #3. This is the backbone; retrofitting `groupId` after launch means reworking the claim query under load.
3. **Stage-level caching** (#4) — every retry that re-spends a Gemini request is a direct cut to daily capacity.
4. **Junk guard + uncertainty marker in the extraction schema** (#6, #7) — these are what make the light-OCR strategy safe rather than reckless.
5. **Error-message mapping for yt-dlp failures** (#9) — silent capture makes the failure notification the entire experience.
6. **Share-sheet state machine with `alreadyExists` and an escape hatch** (#11).
7. **Per-stage idempotency flags** (#12).

### After MVP

- RRF hybrid search (#5) — ship keyword-only first; RRF slots in when embeddings are populated and costs a day.
- Structured embedding profiles (#8) — matters once there's enough corpus for semantic search to beat keyword.
- Model fallback chain (#10) — a config change covers the first incident; automate after it happens twice.
- Rule engine, SSE progress, candidate-image override.

### Long-term

- Import workers for bulk migration (a growth lever once there's something to migrate *to*).
- Webhooks and outbound integrations.
- Offline-first local queue beyond React Query persistence.
- Knowledge-graph relationships across saves — the biggest unoccupied opportunity, and the one that needs the most corpus before it's worth anything.

---

## What this changes in our plan

- **Open decision #8 (job queue) is now settled with detail** — Postgres `SKIP LOCKED` plus `idempotencyKey`/`priority`/`groupId`/retry-after. Karakeep's interface is the spec; its plugin layer is not.
- **Open decision #11 (failure UX) has a concrete answer** — an error-signature → user-message mapping table, seeded from pick-a-recipe's yt-dlp cases.
- **Open decision #12 (observability) shrinks** — one `gemini_calls` table, not a tracing stack.
- **Instagram is confirmed as the hardest source** — pick-a-recipe needs curl-cffi impersonation for *public* Reels. Budget time for it, and treat unauthenticated failure as an expected outcome.
- **The one-call design is validated by contrast.** pick-a-recipe's three-calls-plus-video-upload per save would cap us at ~166 saves/day. That is the clearest evidence that our constraint-driven architecture is correct rather than merely frugal.
