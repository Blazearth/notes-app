# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state

Monorepo: `api/` (Spring Boot), `app/` (Expo SDK 57 + expo-router), `docs/`. Git-initialised.

**Phase 1 backend is done and verified end-to-end, the Phase 2 job runner is in, Phase 3's Gemini classify-and-extract call is live, and Phase 4's OCR tier is in and live-verified.** Flyway `V1__init.sql` (full schema), Supabase JWT resource server, `POST/GET /v1/saves`, and a Postgres-backed queue that is now drained by a real runner — `FOR UPDATE SKIP LOCKED`, per-group exclusion so one user cannot occupy a 1–2 slot pool, three error paths, and a stale-claim sweep. The extraction cascade behind it is in too — a `--dump-single-json` probe, then platform captions, VTT-to-prose, and a yt-dlp error table that decides permanent vs retryable. **It now runs against a real yt-dlp** (2026.07.04, with ffmpeg, both on the dev machine's PATH); the opt-in `YtDlpLiveTest` drives it against a live video. **Steps 3 and 4 are in as of 2026-07-31**: ASR (yt-dlp audio download → ffmpeg downmix → Groq Whisper, a different provider so it costs no Gemini RPD) and the non-video branch (Readability4J for plain links, PDFBox for PDFs) — an "unsupported URL" from yt-dlp's probe now falls through to link extraction instead of failing the save, and a `.pdf` URL skips yt-dlp entirely. Unlike the caption path and the Gemini call, **neither has been run against a real network yet** — 46 new tests all mock the HTTP/process layer (`GroqClient` and `LinkExtractor`/`PdfExtractor` bind `MockRestServiceServer`, matching the pattern `GeminiClient` established), so the "mocked process hides real bugs" lesson from both earlier incidents has not yet been tested against this code.

**Phase 4's visual tier — keyframes, local OCR and the escalation gate — landed 2026-08-01 and is live-verified against real ffmpeg 8.0 and real tesseract 5.5.0.** `pipeline/ocr/` holds the whole tier: a bounded worst-quality video download, one ffmpeg pass that cuts deduped keyframes into a colour branch and an OCR-prepped grey branch, tesseract read per frame in TSV mode for its per-word confidence, a vote across frames, and a quality gate that either passes the text to the ordinary cheap classify call or escalates the sharpest frames to one Flash vision request. 57 new tests, and — unlike ASR and the link/PDF branch — an opt-in `OcrLiveTest` that drives the real binaries end to end **with no network** (it renders its fixture videos with ffmpeg), so the toolchain check can run anywhere.

**Running the real binaries overturned two things the plan itself specified — see [docs/testing.md](docs/testing.md#what-running-the-real-binary-found-the-second-time-visual-tier-2026-08-01).** Both are worth carrying into any future frame or OCR work:

- **`select='gt(scene,0.25)',mpdecimate` selects *zero* frames from a static card.** Frame 0 has no predecessor to differ from and nothing afterwards changes, so scene detection never fires — on precisely the overlay-only Reel this tier exists for. The selector must also take frame 0 (`+eq(n,0)`) and a periodic sample (`+not(mod(n,150))`), with `mpdecimate` left in to collapse the duplicates that adds. Measured: 0 frames before, 1 after.
- **Histogram equalisation makes overlay OCR dramatically worse.** The documented "upscale, grayscale, CLAHE contrast" advice assumes a photographic background with a gradient to flatten; overlay text is high-contrast and bimodal *by design*, and a global remap hollows the glyphs. Same frame, real tesseract: plain upscale+grey read **16 of 16 words at mean confidence 95**; adding `histeq` read **7 garbled tokens at 22** and lost a line. 22 is below the escalation floor, so the "recommended" preprocessing would also have spent a Flash vision request fixing damage it caused.

Also confirmed on real content: tesseract's failure shape is **confident** symbol soup (`|` at 72, `=` at 91 on a talking-head clip), so a gate watching per-word confidence alone waves it through. The alphabetic-token-ratio signal is what catches it.

**Running the real yt-dlp broke three assumptions earlier — same doc, [first section](docs/testing.md#what-running-the-real-binary-found).** Two are worth carrying into any future yt-dlp work: **`--sub-langs` entries are regexes, and yt-dlp synthesises translated tracks named `<source>-<target>`**, so `en.*` matched 29 languages and earned an HTTP 429 partway through one fetch — always use exact codes. And **auto-generated VTT is several times the size of an uploaded track while carrying less text** (43,947 bytes → 4,284 chars of prose, against 8,456 → 4,443), so ranking caption files by size systematically picks the worse source; rank on parsed prose instead. Also: yt-dlp exits non-zero if *any* requested track fails, so read the output directory before trusting the exit code — a rule the frame extractor now follows too.

**Everything downstream is a `JobHandler`, not a change to the runner.** The cascade, the Gemini call, enrichment and embedding each register a handler keyed by `jobs.type`. Outcomes are signalled by exception: `RetryAfterException` reschedules *without* spending an attempt (this is what stops a quota rejection from burning the retry budget), `PermanentJobException` fails immediately with a user-safe message, anything else costs an attempt and backs off exponentially.

**The Gemini call has run against the real API and produced real structured saves — twice, watched live, on 2026-07-30.** That run also surfaced a decoding bug: Gemini's response carries no charset on its `Content-Type` header, and reading it with `.body(String.class)` let Spring guess a charset instead of following the JSON spec's UTF-8 default — accented text arrived as mojibake (`café` → `cafÃ©`). Fixed in `GeminiClient.classify` by reading `.body(byte[].class)` and letting Jackson's byte-based `readTree` decode it directly. That fix, the primary→fallback model routing and budget guard, and the response-schema registry now have unit test coverage that did not exist before this fix landed — `KnowledgeTypeRegistryTest`, `GeminiBudgetServiceTest`, `GeminiClientTest`, `ClassifySaveHandlerTest` (25 tests). `GeminiClientTest` binds `MockRestServiceServer` to an injected `RestClient.Builder` rather than mocking the HTTP layer away, so its regression test genuinely reproduces the mojibake when pointed at the old `.body(String.class)` code and passes against the fix — verified both ways, not just written to pass.

The full create path has run against live Supabase: sign in (ES256 JWT) → `POST /v1/saves` 202 → `GET /v1/saves/{id}` 200 → list 200. That covers the `@CurrentUser` resolver, the lazy profile upsert, the JSONB mapping, and a commit through the transaction pooler. **Supabase signs with ES256, and `NimbusJwtDecoder` accepts RS256 only by default** — the explicit `.jwsAlgorithm()` call in `SecurityConfig` is load-bearing, and the failure mode is a misleading "no matching key(s) found". Per-area status is in [README.md](README.md#whats-real-vs-stubbed).

**`POST /v1/saves` is now idempotent on a repeated `Idempotency-Key` header** (`V2__save_idempotency.sql`: a partial unique index on `saves (user_id, idempotency_key)`). A retried request — including two concurrent retries racing each other — returns the existing save instead of minting a second one; `SaveServiceTest` covers the happy path and the race. This is a different layer from the job queue's own dedupe: `JobQueue.enqueue` only stops a duplicate *job* for a save id that already exists, so it never protected against the duplicate save id being created in the first place. No caller sends the header yet — the Paste Link tile doesn't need it, and the iOS share extension that will (its background `URLSession` retries a POST on the OS's schedule, not the caller's) is still unbuilt.

**The Expo app signs in and creates saves — but has never run on a device.** Home / Library / Spaces / Capture / Settings from the Claude Design mockups, plus a theme and personalisation layer ported from [PennyWise](https://github.com/sarim2000/pennywiseai-tracker) — two surface families × 13 accents × light/dark/AMOLED, cover gradients, font and nav-style choices, all persisted. Supabase email/password auth, the Home feed from `GET /v1/saves`, and the Capture sheet's Paste Link tile posting to `POST /v1/saves` are wired; Library, Spaces, the Continue rail and the digest are still `app/src/data/sampleContent.ts`, because no endpoint serves them.

**The Home feed renders type-specific cards, not a flat row, for `ready` saves.** `buildCardModel` (`app/src/saves/cardModel.ts`) turns a save's `knowledgeType` + `structuredData` into a recipe/movie/place layout — ingredient or highlight chips, a meta line, a synopsis line — via `SaveCard`, and falls back to the existing flat `ListRow` for anything still processing, `unusable`, or a knowledge type without a bespoke layout yet. Found in the process: `place`'s title field is named `name`, not `title` (`KnowledgeTypeRegistry`), and `saveTitle()`'s generic fallback was silently missing it — fixed alongside.

**A motion layer sits on top of that** — `react-native-reanimated` (already a dependency, previously unused) now drives every press, the tab-switch cross-fade, and a staggered content reveal per screen, plus `expo-haptics` gated behind a `haptics` preference. All of it is verified the same way as everything else app-side: typechecks, bundles for web/Android, and mounts clean in a browser dev server — not run on a device, so the haptic feedback specifically is unverified.

**Android's half of silent capture is built, as a config plugin, not a hand-edited `android/`.** `app/plugins/withAndroidShareReceiver.js` adds a no-display `ShareReceiverActivity` (`ACTION_SEND` / `text/plain`) and a `ShareUploadWorker` — a `CoroutineWorker` that POSTs to `/v1/saves` under a network `Constraints`, exponential backoff, and a per-share idempotency key generated once at enqueue and carried through every retry. Both run in-process, so unlike iOS there is no separate-process boundary to bridge across: `app/src/share/nativeShareConfig.ts` mirrors the session and the `openAppWhenSaving` toggle into a plain JSON file in the app's private files dir (`expo-file-system`'s `Paths.document`, which *is* `context.filesDir` on Android), and the native side reads it directly. Verified by regenerating the native project (`expo prebuild -p android --clean`) and inspecting the output — manifest entry, Gradle dependency and Kotlin sources all land correctly and idempotently on a second run — and by typechecking; **not run on a device or emulator**, since this machine has no Android SDK. One known gap: the mirrored access token can go stale while the app is backgrounded (the refresh timer is deliberately stopped then, same as the API client), and nothing yet re-mints it inside the worker — see the root README's Known gaps.

**A pom.xml gap meant the API could not boot at all, and no unit test had caught it.** `GeminiClient`, `GroqClient`, `LinkExtractor` and `PdfExtractor` all inject `RestClient.Builder` assuming Spring Boot auto-configures a prototype-scoped bean for it — true through Boot 3.x, but Boot 4 split that autoconfiguration out of `-webmvc` into `spring-boot-starter-restclient`, never added here. Every test binds a `RestClient.Builder` by hand, so none of them boot a real `ApplicationContext`. Found and fixed by actually running `spring-boot:run` and hitting `POST /v1/saves` with `curl` end to end — real sign-in, a real `202` with a `Location` header, a repeated `Idempotency-Key` returning the same save (the exact contract the Android worker above relies on), and a no-auth request returning the expected empty-body `401`. See the root README's "Also on 2026-07-31" for the full trace.

**It typechecks and bundles, and that is all that has been proven.** There is no dev build, so no screen has been seen rendered and no request has left a device. Treat every app-side claim as unverified until `npx expo run:android` says otherwise. Details and the contrast-picking rationale are in [app/README.md](app/README.md).

**Phase 5's search half landed 2026-08-01 and is verified end to end against live Supabase** — `embed/` and `search/`. A save reaches `ready`, then a separate `embed_save` job embeds a `label: value` profile built from `structured_data` (CA#8 — never the raw caption, which is dominated by filler), and `GET /v1/saves/search` fuses Postgres FTS with pgvector by Reciprocal Rank Fusion (k=60). Proven live: *"somewhere nice to eat in Denmark"* returns a save whose text says **Copenhagen** and never Denmark. Four things worth carrying forward, all of which only the live run showed:

- **`to_tsvector(text)` is STABLE, not IMMUTABLE**, so a generated column must use the two-argument `to_tsvector('english', …)`. And `jsonb_path_query_array(data,'$.*')::text` indexes values only, where `data::text` also indexes the JSON **keys** — "name" and "ingredients" would match every save. Both confirmed by asking the database, because either wrong fails a migration and leaves Flyway needing repair.
- **V1's `search_tsv` could not find a restaurant by its name.** It read `structured_data->>'title'`, but `place`'s field is `name` — the same trap that broke `saveTitle()` app-side — and `movie` uses `synopsis`, not `summary`. Arrays were invisible entirely. V3 replaces it with a weighted vector (A=title/name, B=prose, C=everything else); V4 strips the `[unclear]` sentinel, which appears in most saves and was a term nearly all of them shared.
- **`gemini-embedding-001` is Matryoshka: a 1536-d request is a truncated 3072-d vector, and truncation destroys normalisation** — measured L2 1.000000 full against 0.691743 truncated. Cosine hides it; `<->` and `<#>` would not. Normalise client-side. Confirmed too that the API returns 3072 by default and 1536 only when `outputDimensionality` is sent.
- **A k-NN search has no "no match".** Before a distance cutoff existed, `zzzzqqq` returned the user's whole library. The cutoff (0.40) was measured — real hits land at 0.27–0.37, real misses at 0.42–0.50 — but on three saves and seven queries, so treat it as provisional.

**Embeddings are the one Gemini call not behind `BudgetApproved`, deliberately** — their pool is separate from the generation models', so counting them would throttle saves for nothing. That exception is stated loudly in `EmbeddingClient` precisely because the rule it breaks is otherwise absolute.

**The escalation gate's thresholds are guesses, and that is the tier's real open item.** `min-mean-confidence: 60` was sanity-checked against two extremes only — a clean synthetic card (95) and a textless clip — never measured. The thirty-Reel eval set in Phase 4 is what turns it into a number, and both failure directions are silent without it: too strict burns the 20-RPD Flash pool, too loose ships invented ingredients nobody reports. Everything the tier has read so far has been a fixture it generated itself; real text over photographs, motion blur and stylised fonts are exactly where tesseract fails hard rather than gracefully, and none of that is covered.

Build and test — JDK 25 and Node for the app, Maven via the wrapper:

```bash
cd api && ./mvnw test          # 184 unit tests, no database needed (5 opt-in live tests skip)
cd api && WEAVR_LIVE_OCR=1 ./mvnw test -Dtest=OcrLiveTest   # real ffmpeg + tesseract, no network
cd api && ./mvnw spring-boot:run
cd app && npm run typecheck
cd app && npx expo export --platform android   # bundles without a device
```

Secrets live in a gitignored `.env` at the repo root; [.env.example](.env.example) is the tracked template.

**[docs/testing.md](docs/testing.md) is the testing guide** — what each check proves, the traps (two DB URLs, a stale server on 8080, `EXPO_PUBLIC_*` inlined at build time), and the honest list of what is not covered. Read it before claiming something works: *typechecks*, *bundles*, *boots* and *runs on a device* are four different claims, and only the first three can currently be made about the app.

**Spring Boot 4, not 3** — most tutorials you'll find are wrong in two ways: Jackson 3 moved `ObjectMapper` to `tools.jackson.databind` (annotations stayed on `com.fasterxml.jackson.annotation`), and the starters were renamed (`-webmvc`, `-security-oauth2-resource-server`, plus a `-test` companion per starter). A third: `RestClient.Builder` autoconfiguration moved out of `-webmvc` into its own `spring-boot-starter-restclient` — every `RestClient`-based client (Gemini, Groq, link/PDF extraction) needs it, and its absence fails app startup, not compilation, so it doesn't show up until something actually boots the full context.

**The spec is partly superseded.** Stack decisions below override it — see [Where the spec is stale](#where-the-spec-is-stale).

## What is being built

An AI-powered "save anything" mobile app for the RevenueCat Shipaton 2026 hackathon (build window Aug 1 – Sep 30, 2026; two-person team). A user shares any content (Reel, TikTok, screenshot, link, PDF, voice memo) into the app via the OS share sheet; a pipeline classifies it into a *knowledge type*, extracts structured fields, enriches it from external APIs, embeds it for semantic search, and surfaces a type-specific **action** (recipe → shopping list, workout → routine, restaurant → navigate/vote).

The app is **Weavr**. Java package `com.weavr.api`; bundle/package id `com.weavr.app`; Expo slug `weavr`; App Group `group.com.weavr.app`; Keychain access group `com.weavr.shared`. The directory is `saveIt`; that is not the product name.

## Architecture

| Piece | Choice |
|---|---|
| Mobile | **Expo** (React Native + TypeScript) with an EAS dev client |
| Backend | **Java + Spring Boot** — one deployable: REST API *and* the ingestion/AI pipeline |
| Database | **Supabase free tier** — Postgres + pgvector, Auth, Storage |
| LLM | **Gemini 2.5 Flash-Lite** (primary) + **2.5 Flash** (vision / escalation), free tier |

**Supabase is a managed Postgres + Auth + Storage host here, not the backend.** No Edge Functions, no Realtime-driven business logic — Spring Boot owns the API surface, the RevenueCat webhook, entitlement gating, and the pipeline.

**Auth:** the app signs in with the Supabase client and sends the Supabase JWT to Spring Boot, which validates it as an OAuth2 resource server (`spring-boot-starter-oauth2-resource-server`, Supabase's JWKS endpoint). Spring connects to Postgres with a service role, so **RLS is not the access-control boundary for API traffic** — authorization lives in the service layer, keyed off the `sub` claim. Keep RLS policies anyway for any path where the client reads Postgres directly.

**Pipeline placement:** the media and AI work runs inside the Spring Boot service on an async worker pool or a DB-backed job queue — not in the request thread. `yt-dlp`, `ffmpeg` and `tesseract` are invoked as external binaries via `ProcessBuilder`; **the Docker image must install all three** (a plain JRE base image has none of them), plus `tesseract-ocr-eng` — an install without language data reads every frame as nothing, silently.

## Capture flow: silent by default

**Sharing must not open the app.** The user taps Share on a Reel, picks us, sees a brief confirmation, and is back in the Reel — the app never comes to the foreground. Opening the app is an opt-in setting, off by default. Everything downstream (download, transcript, keyframes, Gemini) happens server-side; the client's only job is to hand off a URL fast and get out of the way.

**Settings toggle:** *Open app when saving* — default **off**.

### Android

An intent-receiving Activity with a no-display theme (or a Service): receive the intent, enqueue with WorkManager (network constraint + retry), show a Toast, `finish()` immediately. Standard pattern, no real obstacles.

### iOS — the constraint that drives the design

This requires a **custom Share Extension**, which means [`expo-share-extension`](https://github.com/MaxAst/expo-share-extension), not `expo-share-intent`. `expo-share-intent` works by handing off to the main app — opening the app *is* its model, so it cannot deliver silent capture. **The spec files this as a §5 stretch goal; it is now a week-1 requirement.**

Share extensions are a separate, short-lived process with a hard memory ceiling. They are killed quickly and cannot be relied on to finish network work. So:

- **Never download, transcode, or wait on an API response inside the extension.** Post the URL (or image data) to the Spring Boot API with a **background `URLSession` configured with `sharedContainerIdentifier`**, then call `completeRequest` immediately. The system completes the transfer after the extension dies, and retries when the device is offline. Payloads are tiny — a URL string, not media — so this is fast.
- **Auth needs a Keychain access group**, not the App Group container. The extension can't reach the app's JS-side storage. Share the token via `kSecAttrAccessGroup`.
  **Token expiry is a real failure mode:** Supabase access tokens are short-lived and the extension will often run with an expired one. Store the *refresh* token in the shared Keychain and refresh inside the extension, or give the API a path that accepts a refresh token directly. Decide this before writing the extension — retrofitting it is painful.
- **The toggle must be mirrored into the App Group at write time.** The extension cannot read AsyncStorage. Write the setting to `UserDefaults(suiteName: "group.…")` whenever it changes in the app, and have the extension read it from there.
- **Confirmation UX:** the extension can render a minimal "Saved ✓" that auto-dismisses in well under a second (the Pinterest pattern) — this is better than a silent dismissal, which reads as a failure. The "structured card is ready" signal is the push notification already in the spec.
- **"Open app" mode is the easier path, not the harder one** — it's what `expo-share-intent` does natively. But opening the containing app from a *custom* share extension is historically unreliable (`NSExtensionContext.open` has never worked dependably for share extensions); the practical route is the URL-scheme redirect that `expo-share-intent` uses. Verify this early — it decides whether the toggle is cheap or a second integration.

Once a custom extension exists, a third mode is nearly free: a **quick sheet** inside the extension (pick a Space, add a note) without leaving the host app. Worth offering as the middle setting between silent and full app launch.

## The request budget: 500 RPD is the whole app's daily AI capacity

Gemini free tier caps **requests per day** on the generation model, not spend. Tokens-per-minute is generous by comparison. This inverts the usual optimization:

> **Requests are scarce. Tokens are cheap. Spend tokens freely to avoid a second request.**

Every architectural choice below follows from that. Consequences to internalize:

- **One Gemini request per save.** Not classify-then-extract, not a separate vision call. One multimodal request carrying transcript + metadata + keyframes, returning `knowledge_type` *and* the type-specific fields together. At 2 requests/save the app's ceiling halves to ~250 saves/day across all users.
- **Because tokens are free-ish, buy quality with them** — more keyframes, richer prompts, few-shot examples per knowledge type, and thinking enabled. None of that costs a request. This is how you avoid quality loss from collapsing the two calls.
- **Saves are not the only consumer.** Weekly digests, AI Project Builder, and Act conversions each cost requests, and digests scale with user count. Budget them explicitly and stagger digest generation; don't let a Sunday-night digest job eat the day's save capacity.
- **Reserve headroom and degrade gracefully.** When the daily budget is spent, queue saves as `pending` for the next window and tell the user, rather than failing them. Track consumption in Postgres, not just in memory — the counter must survive a restart.
- **Embeddings are a separate, larger pool** — they do not consume the generation RPD. Embed freely; re-embedding backfills are not a budget event.
- **Every model has its own pool, so routing across models multiplies daily capacity.** Flash-Lite (~500 RPD) and Flash (~250 RPD) together are ~750 saves/day, not 500. Route by difficulty rather than sending everything to one model — this is the single largest capacity lever available on the free tier, and it is worth more than any prompt optimization.
- Check whether the **Batch API** has a separate quota. If it does, non-urgent work (digests, Project Builder) belongs there and frees the interactive budget for saves. Verify before designing around it.

> ⚠️ **Verify the actual RPD in [AI Studio](https://aistudio.google.com/rate-limit) — it is per-project and Google no longer publishes it in the docs.** Third-party trackers currently report **250 RPD / 10 RPM** for `gemini-2.5-flash` free tier, not 500, following a reduction in December 2025. If it is 250, the one-request-per-save design isn't just an optimization — it is the only thing that keeps the app viable, and the paid-tier switch moves earlier. Do not size the launch against a remembered number.

**This collides with the growth strategy.** §11 of the spec targets viral growth, and the Grand Prize is judged on traction *during* the event — but 500 RPD is roughly 400 saves/day after headroom. A successful launch exhausts the free tier immediately. Plan the paid-tier switch as a launch prerequisite, not a scaling problem for later.

## The text-extraction cascade (the core of the worker)

**Never send video or audio streams to Gemini.** The worker's job is to assemble a text blob plus a small set of images, then make exactly one model call. Try the free sources first and stop at the first that yields usable text:

1. **Platform captions/subtitles** — `yt-dlp --write-auto-subs --write-subs --skip-download`. Free, zero requests, covers most YouTube and many TikTok/Reels. Primary path, not a fallback.
2. **Post metadata** — `yt-dlp --write-info-json`: title, description, caption, hashtags, uploader. Frequently enough on its own.
3. **ASR, only when 1 and 2 fail** — `ffmpeg` downmixes audio to 16 kHz mono, then a hosted Whisper endpoint (Groq `whisper-large-v3-turbo`), or local `whisper.cpp` for zero external dependencies. This is a *different provider*, so it does not consume Gemini RPD.
4. **Keyframes** — see below. These attach to the same single Gemini request as images.

Screenshots and photos go through on-device OCR (Tier 0 below). Links get readable-text extraction. PDFs get text extracted before the call.

### Handling the video itself

The video is a means to text and one thumbnail. Never treat it as an artifact to keep.

- **Usually don't download it at all.** Step 1 uses `--skip-download`; if captions or a rich description exist, the pipeline ends without a byte of video transferred. This is the common case and the whole reason the cascade is ordered this way.
- **When you do need frames, take the worst stream that still has legible text** — `-f 'worst[height>=360]'` or similar. You are OCR-ing overlay text sized for phone screens; 360–480p is plenty and often an order of magnitude smaller than the default.
- **For ASR, download audio only** (`-f bestaudio`), never the video track.
- **Pipe, don't store.** `yt-dlp -o - <url> | ffmpeg -i pipe:0 …` extracts frames without the video ever touching disk. On a small container with a small ephemeral disk, concurrent jobs writing full videos is what fills it.
- **Bound everything.** `--download-sections "*0-90"` caps long-form YouTube; Reels and Shorts are short anyway. Without a cap, one 3-hour video stalls a worker.
- **Nothing video-related goes to Supabase Storage.** Process in a temp dir, keep the thumbnail, delete the rest in a `finally` block — storage cost, legal exposure, and the ~1 GB free-tier ceiling.

**Java process-handling pitfalls** (these cause hangs, not errors, so they're hard to diagnose later):

- **Drain stdout and stderr on separate threads, or use `ProcessBuilder.redirectOutput`.** A process that fills the pipe buffer while nobody reads it blocks forever. This is the single most common `ProcessBuilder` bug and it looks exactly like "yt-dlp is slow."
- **Always `waitFor(timeout)` then `destroyForcibly()`.** A hung yt-dlp otherwise pins a worker permanently, and with a small pool that's the whole pipeline.
- **Limit job concurrency to 1–2.** ffmpeg and OCR are CPU- and memory-hungry; on a free-tier instance, parallel jobs OOM rather than queue.

**Operational reality of yt-dlp** — plan for it rather than being surprised:

- **Extractors break when platforms change.** This is routine, not exceptional. Decide now whether the container pins a version (reproducible, breaks silently over time) or updates on start (stays working, less reproducible). For a two-month hackathon, update-on-start is usually the right trade.
- **Instagram increasingly requires authentication.** `--cookies` with a personal account risks a ban and sits badly against the ToS posture in spec §12. Treat unauthenticated failure as an acceptable outcome before reaching for cookies.
- **Datacenter IPs get blocked more aggressively than residential ones.** Expect a nonzero baseline failure rate from cloud hosting, and make sure the failure UX (open decision #11) covers it — silent capture means nobody is watching when this happens.

### Overlay-text-only content (the hard case)

A recipe Reel whose ingredients exist **only as on-screen overlay text** — no caption, no description, no voiceover — defeats steps 1–3 entirely. This is common and must work.

**Do not use the vision model as the OCR engine.** Vision calls are the most expensive path in the system and they burn the scarcer Flash pool. Read the text locally, then feed the *text* into the cheap Flash-Lite call like any other save. Vision becomes the fallback, not the default.

**Tier 0 — on-device OCR (zero server cost, zero model calls).** Screenshots and photos are already on the phone at share time. Run **Apple Vision** (`VNRecognizeTextRequest`) on iOS and **ML Kit Text Recognition** on Android inside the share flow, and send the extracted text with the payload. Both are free, offline, fast enough for the extension's time budget on a single image, and genuinely good at UI/screenshot text. This removes an entire category of saves from the vision path.

**Tier 1 — server-side local OCR on keyframes.** For video, the device only has a URL, so this runs in the Spring service:

- **Extract and dedupe frames in one ffmpeg pass** — `mpdecimate` stops a 5-second static ingredient card from becoming 5 near-identical frames. ⚠️ **The obvious selector is wrong and was corrected by measurement:** `select='gt(scene,0.25)',mpdecimate` yields *zero* frames on a fully static card, because frame 0 has nothing to differ from and nothing after it changes. Take frame 0 and a periodic sample too:
  `select='gt(scene,0.25)+eq(n,0)+not(mod(n,150))',mpdecimate` — and note `-vsync vfr` is now spelled `-fps_mode vfr`. The live implementation splits this one decode into a colour branch (768px, for the thumbnail and any vision escalation) and a grey branch (2× upscale, for tesseract); splitting *after* the selection is what keeps the two lists index-aligned, which two separate ffmpeg runs over a stateful `mpdecimate` would not.
**OCR only has to be good enough for the LLM to repair.** `1 cup fl0ur` and `2 tbsp 0live oi1` reconstruct perfectly with recipe context, so don't pay for accuracy the model gives you free. Start at the lightest option and only move up if measurement forces it:

- **Default: Tesseract with `tessdata_fast`** (`--psm 6` or `11`). Runs as an external process like yt-dlp/ffmpeg, so it costs no JVM heap; the fast English model is ~2 MB. This is the lightest thing that produces usable output.
- **Upgrade path if that's not enough: [RapidOCR](https://github.com/RapidAI/RapidOCR) mobile models** — PaddleOCR det/rec converted to ONNX with Java bindings, so it runs in-JVM via ONNX Runtime with no Python sidecar. Markedly better on stylized and scene text; costs single-digit MB of models plus ONNX Runtime in container RAM. Adopt only if the eval set says Tesseract's output is below the repair floor.
- **Know where the repair floor is.** LLM reconstruction works when OCR is roughly 70–80% correct. It does *not* work at 20% — and Tesseract fails hard, not gracefully, on low-contrast text over gradients and video. Below that floor the model doesn't repair, it hallucinates plausible ingredients, which is worse than failing. The confidence gate in Tier 2 is what makes "go light" safe; don't skip it.
- **Preprocess, but less than you think — measured, not assumed.** Upscale (2×, lanczos) and greyscale, and **stop there**. The usual next step — CLAHE, or ffmpeg's `histeq` — actively destroys overlay text: it is high-contrast and bimodal *by design*, so a global histogram remap flattens the background to mid-grey and hollows the glyphs. Same frame, real tesseract: plain upscale+grey read 16 of 16 words at mean confidence 95, `histeq` read 7 garbled tokens at 22 and lost a line. And 22 is under the escalation floor, so it would have spent a Flash vision request repairing its own damage. The chain is a property (`weavr.ocr.frame-filters`) precisely so a genuinely low-contrast source can get a contrast stage without a code change — but the default must not.
- **Vote across frames.** Burned-in overlays persist for many frames, so the same string is read repeatedly. Consensus across duplicates corrects individual errors for free — the cheapest accuracy win available, with no analogue in a one-shot vision call.

**Tier 2 — escalate to Flash vision, only on failure.** Use RapidOCR's per-word confidence plus a schema sanity check (a recipe should yield quantities and units; a product should yield a price or brand). If confidence is low or the extraction doesn't validate, *then* send the frames to Flash. Expect this to be a minority of visual saves.

**Set the escalation threshold empirically.** Build a labeled set of ~30 real Reels — overlay-only recipes, workout cards, product shots — and measure local OCR against Flash on it. Without that, the threshold is a guess and you'll either burn the Flash pool needlessly or ship silent extraction failures.

Only run any of this when steps 1–3 produced nothing usable, or produced text whose type carries its payload visually (recipe, workout, product).

## AI pipeline

Per save, **one Gemini request** — routed to a model by difficulty:

| Input | Model | Why |
|---|---|---|
| Clean text — captions, links, PDFs, typed notes, **and OCR output that passed confidence** | **2.5 Flash-Lite** | Once OCR has run, an overlay-only Reel is just a text save. This should be the overwhelming majority of traffic. |
| Frames where local OCR failed or failed schema validation | **2.5 Flash** (vision) | The genuine fallback. A misread ingredient card is a failed save, so don't economize *here* — economize by rarely getting here. |
| Flash-Lite returned low `confidence` | **2.5 Flash** (retry) | Quality backstop on the minority of saves that need it. |

Steps:

1. **Assemble** — transcript/captions/metadata text blob + selected keyframes.
2. **Single classify-and-extract call** — returns `knowledge_type`, `confidence`, and the type-specific structured fields together. Force JSON with `responseMimeType: "application/json"` and a `responseSchema`; use `anyOf` over the per-type schemas so the model picks the right shape. Never parse free-form text.
   - **Enable thinking explicitly.** Flash-Lite's default thinking budget differs from Flash's (Lite ships with it effectively off). Tokens are not your constraint — turn it on and buy back the quality gap. Verify the current default before assuming.
   - Track which model served each save alongside `confidence`, so escalation rate is measurable rather than guessed.
3. **Enrichment** — TMDB / Google Books / Spotify / Google Places by type, merged into `structured_data`. Not Gemini; doesn't consume RPD.
4. **Embedding** — summarize `structured_data`, embed with `gemini-embedding-001`, store in `saves.embedding`. Separate rate-limit pool from generation, so this is not a second draw on the save budget.
5. Set `status = ready`, push notification, run duplicate detection if the save landed in a shared Space.

Keep per-type few-shot examples and schemas in a registry keyed by `knowledge_type`, so adding a type is a data change rather than a code change.

## Non-obvious constraints

**Gemini free tier**

- **500 requests/day, per project — the app's total daily AI capacity.** See [the request budget](#the-request-budget-500-rpd-is-the-whole-apps-daily-ai-capacity); it drives the one-request-per-save design. There is also a requests/minute cap, so the job queue needs global throttling and retry-with-backoff from day one — not per-user fairness bolted on later.
- **Persist the daily counter in Postgres** with the reset boundary in the same timezone Google resets on. An in-memory counter loses the day's consumption on every deploy and silently over-spends.
- **Free-tier prompts and responses are used to improve Google's products.** The app ingests users' personal screenshots, photos, and possibly PDFs and emails. Disclosing it in the privacy policy is necessary but not sufficient: Apple's App Privacy questionnaire and Google Play's Data Safety form both require declaring third-party sharing and its purpose, and "used for model improvement" is a materially different answer than "processed to provide the service." Fill those forms against what the free tier actually does, and revisit both if you move to a paid tier where the terms differ.
- Java integration: the Google Gen AI SDK for Java (`com.google.genai:google-genai`), or plain REST via `RestClient`. Verify the current artifact version before pinning.

**Supabase free tier**

- **Two different connection paths, and mixing them up breaks things:**
  - *Runtime* → **Supavisor transaction pooler**. Free-tier direct connections are few and a HikariCP pool will exhaust them. Keep the pool small (~5–10) and disable server-side prepared statements (`prepareThreshold=0` on the PG driver), or you'll hit intermittent prepared-statement errors.
  - *Flyway migrations* → **session pooler or direct connection**. Flyway takes a session-level advisory lock and runs DDL in transactions; both break under the transaction pooler. Configure `spring.flyway.url` separately from `spring.datasource.url`. This one is easy to miss because it usually works on the first migration and fails later under concurrency.
- **Flyway specifics:** `CREATE EXTENSION IF NOT EXISTS vector` belongs in `V1__*.sql`. The embedding column's dimension is baked into the DDL, so changing embedding models is a migration *plus* a re-embedding backfill — treat it as a one-way door and pick the model before writing V1.
- **Projects pause after ~7 days of inactivity** — a paused project during judging is a demo-day failure. The keep-warm cron must issue a real query (a trivial `select` through PostgREST or the API), not just an HTTP ping to a static endpoint. Two gotchas if it runs on GitHub Actions: scheduled workflows are **disabled automatically after 60 days of repo inactivity**, and cron triggers are best-effort and can be delayed under load — don't schedule at exactly the 7-day boundary.
- ~500 MB database, ~1 GB storage.

**Java/Postgres mapping**

- JSONB with Hibernate 6: annotate with `@JdbcTypeCode(SqlTypes.JSON)`.
- pgvector has **no native Hibernate type** — use the `pgvector-java` library or drop to `JdbcTemplate` for reads/writes of the embedding column and for similarity queries. Plan this before designing the entity layer.

**Product/legal**

- **RevenueCat entitlements must be enforced server-side.** RevenueCat webhook → a Spring endpoint → `subscriptions` table, so the pipeline can gate model calls. A client-only check is trivially bypassed, and the gated resource is the expensive one.
- **Free-tier caps (20 AI saves/month, 1 Act conversion/week) belong in the worker from week 1.** With Gemini's free tier the cost is rate limit, not dollars — but the cap is what stops one user from starving everyone.
- **`expo-share-extension` and `react-native-purchases` are native modules** — an EAS dev client is required from day one. Expo Go will not work.
- **Only ingest content the user themselves shares.** No bulk scraping, no public rehosting of downloaded media.
- **Karakeep and Linkwarden are AGPL-3.0** — study the architecture, never copy code into this closed-source app. Source-level teardown of both (plus three recipe-pipeline repos) is in [docs/competitive-analysis.md](docs/competitive-analysis.md), including the ranked steal list and an explicit do-not-adopt list. All findings there are architectural; no code was copied.

## Data model

Schema sketch is §6 of the spec. Shape decisions to preserve:

- `saves.structured_data` is **JSONB, not per-type columns** — ~20 knowledge types with divergent schemas, and new types must not require a migration.
- Search is **hybrid**: Postgres full-text *and* pgvector in one query. No separate search service.
- `saves.embedding` is `VECTOR(1536)`, using `gemini-embedding-001`. That model's default output is 3072, with 768 / 1536 / 3072 the recommended values (128–3072 supported via truncation) — **1536 must be requested explicitly via `outputDimensionality`**, and it keeps the spec's original column width. The dimension is baked into V1 DDL, so changing it later is a migration *plus* a full re-embedding backfill.
- Spaces + `space_members` with `owner|editor|viewer` roles carry all collaboration; a save belongs to a user and optionally a space.

## Where the spec is stale

| Spec says | Actual |
|---|---|
| §4, §8: Supabase Edge Functions as glue; Node/Python worker on Railway/Fly | One Spring Boot service owns API + pipeline |
| §4, §8: Supabase Auth/Realtime/Storage as the backend | Supabase = Postgres + Auth (JWT verified by Spring) + Storage only |
| §7, §8: Claude for classification/extraction/vision | Gemini 2.5 Flash, free tier |
| §7 steps 4–5: separate classification call, then extraction call | **One** combined multimodal call per save — 500 RPD makes requests the scarce resource |
| §7: video → ffmpeg → Whisper as the default path | Captions/metadata first; ASR only as fallback; never raw video to the model |
| §8: OpenAI/Voyage embeddings | `gemini-embedding-001` (separate rate-limit pool from generation) |
| §6: `VECTOR(1536)` | Still 1536 — but request `outputDimensionality: 1536` explicitly; the model defaults to 3072 |
| §5: `expo-share-intent`; share opens the app to a "Saving…" screen; `expo-share-extension` a stretch goal | **Silent capture is the default UX.** `expo-share-extension` from week 1; opening the app is an opt-in toggle |

Still current: §2 (feature scope), §3 (monetization), §9 (RevenueCat), §10 (build order), §11 (growth), §12 (legal/privacy), §13 (submission checklist).

## Build sequencing

§10 of the spec lays out an 8-week order (foundation → AI pipeline v1 → types & enrichment → organize → act → collaborate → monetization → growth). It is a *sequencing plan, not a cut list* — everything in §2 is in scope. Keep something demoable at every stage.
