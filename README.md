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

## Status — Phase 1 done, Phase 2 job runner in, Phase 3 Gemini call live, Phase 4 OCR tier in

### What's real vs. stubbed

| Area | State | Notes |
|---|---|---|
| Flyway `V1__init.sql` | **real, verified** | Applied against Supabase (PG 17.6). All 11 tables, pgvector, HNSW index, generated FTS column, RLS policies |
| Supabase JWT auth | **real, verified** | Issuer + audience checked explicitly. Both paths exercised: rejection (401) and accept, with a real **ES256** token |
| `POST /v1/saves` | **real, verified** | `202` with a real JWT; row committed and readable. Exercises `@CurrentUser`, the lazy profile upsert, and the JSONB mapping |
| `GET /v1/saves`, `/{id}` | **real, verified** | `200` on both; `/{id}` returns the created save, the list returns it too |
| Job queue | **real, verified** | Enqueue runs as part of the create path; the runner drains it |
| Job runner | **real, verified** | `SKIP LOCKED` claim, per-group exclusion, three error paths, stale-claim sweep. Claimed and completed a real job against live Supabase |
| Extraction cascade — metadata + captions | **real, verified** | `--dump-single-json` probe then `--write-auto-subs`, VTT to prose, error classification. Run against yt-dlp 2026.07.04 and a live YouTube video by the opt-in `YtDlpLiveTest`; it found and fixed three defects |
| Extraction cascade — ASR | **built, unverified live** | yt-dlp audio download → ffmpeg 16 kHz mono downmix → Groq Whisper. A different provider with its own key, so it costs no Gemini RPD. 9 unit tests, all mocked — no real Groq call made yet |
| Extraction cascade — link / PDF | **built, unverified live** | Readability4J for plain links, PDFBox for PDFs — the non-video branch, reached when yt-dlp has no extractor for the URL or it's a `.pdf`. 7 unit tests, all mocked — no real page or PDF fetched yet |
| `process_save` handler | **real** | Runs the cascade and parks the text in `save_stages` for `classify_save` to pick up |
| Gemini classify-and-extract call | **real, verified live** | `classify_save` handler + `GeminiClient`/`GeminiBudgetService`/`KnowledgeTypeRegistry`. Ran against the real API and produced real structured saves on 2026-07-30 — see below. 25 unit tests (`GeminiClientTest`, `GeminiBudgetServiceTest`, `KnowledgeTypeRegistryTest`, `ClassifySaveHandlerTest`) |
| OCR tier — frames, tesseract, voting, gate | **real, verified live** | `pipeline/ocr/`. Bounded worst-quality download → one ffmpeg pass cutting deduped keyframes into a colour and a grey branch → tesseract per frame (TSV, for per-word confidence) → vote across frames → quality gate. Driven end to end against real ffmpeg 8.0 and tesseract 5.5.0 by the opt-in `OcrLiveTest`, which needs **no network** — it renders its own fixtures. That run overturned two decisions the plan had specified; see below |
| OCR tier — Flash vision escalation | **built, unverified live** | `GeminiClient.transcribeFrames` sends the sharpest frames as base64 `inline_data` and gets back a verbatim transcription, which then flows through the ordinary classify call. Request shape is pinned by `MockRestServiceServer`; no real vision call has been made |
| Thumbnail / frame ranking | **real** | Variance-of-Laplacian, end-weighted, pure Java, no model call (CA#15). Used today to pick which frames a vision escalation carries; nominating a stored thumbnail needs a Storage path that does not exist yet |
| Search (FTS + vector) | **schema only** | `search_tsv` and `embedding` columns exist and are populated by nobody. Phase 5 |
| RevenueCat / entitlements | **schema only** | `subscriptions`, `usage_counters` tables exist. Phase 5 |
| `POST /v1/saves` idempotency | **real, verified** | Repeated `Idempotency-Key` header returns the existing save, including under a concurrent-retry race (`V2__save_idempotency.sql`, `SaveServiceTest`). No caller sends the header yet — it exists for the still-unbuilt iOS share extension |
| Expo app — theme & personalisation | **real, bundles clean** | 78 palette combinations, all audited for WCAG AA. Preferences persist |
| Expo app — auth + save create/list | **real, never run on a device** | Supabase email/password, `POST`/`GET /v1/saves`, all four feed states. Typechecks and bundles; no dev build exists yet |
| Expo app — type-specific save cards | **real, never run on a device** | `SaveCard` renders recipe/movie/place layouts from `knowledgeType` + `structuredData` for `ready` saves; falls back to a flat row otherwise. Typechecks and bundles |
| Expo app — Library / Spaces / digest | **sample content** | Need pipeline output or collaboration endpoints that do not exist |
| Silent capture — Android | **built, never run on a device** | A config plugin (`app/plugins/withAndroidShareReceiver.js`) adds a no-display `ShareReceiverActivity` + a `ShareUploadWorker` (WorkManager, network-constrained, retried with exponential backoff up to 8 attempts). `expo prebuild -p android` produces the right manifest entry, Gradle dependency and Kotlin sources, verified by inspecting the generated output — no Android SDK on this machine to build or run it |
| Silent capture — iOS | **absent** | The *Open app when saving* toggle exists; the native share extension does not. See [app/README.md](app/README.md) |

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

- **Nothing in the app has run on a device.** The Expo app typechecks and
  bundles; no dev build exists, so no screen has been seen rendered and no
  request has left a device. Sign-in, the feed and save creation are all written
  but unproven — `npx expo run:android` is the next real test, and it is the
  remaining Phase 1 exit criterion alongside the store records. This machine
  has no Android SDK, so it cannot be the one that runs it.

**Correctness**

- **No integration tests against a real database.** Unit tests cover validation
  and enum mapping only. Testcontainers with a `pgvector/pgvector` image is the
  natural next step.
- **`SecurityConfig` accepts `{ES256}` and nothing else.** `jwsAlgorithm()` adds
  to a set rather than extending the defaults. Rotating the Supabase signing key
  to RSA 2048 would break auth entirely, with the same misleading "no matching
  key(s) found". One extra line fixes it; see the gotcha above.
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
