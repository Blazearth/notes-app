# Weavr

Save anything — a Reel, a TikTok, a screenshot, a link, a PDF, a voice memo — and get
back something structured you can act on. Share into the app from the OS share sheet;
a pipeline classifies the content, extracts structured fields, enriches it, embeds it
for semantic search, and surfaces a type-specific action (recipe → shopping list,
workout → routine, restaurant → navigate).

Built for the RevenueCat Shipaton 2026 (Aug 1 – Sep 30, 2026).

- [CLAUDE.md](CLAUDE.md) — architecture and the constraints that drive it
- [docs/implementation-plan.md](docs/implementation-plan.md) — the phase plan
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

## Status — Phase 1 (Foundation)

### What's real vs. stubbed

| Area | State | Notes |
|---|---|---|
| Flyway `V1__init.sql` | **real, verified** | Applied against Supabase (PG 17.6). All 11 tables, pgvector, HNSW index, generated FTS column, RLS policies |
| Supabase JWT auth | **real, verified** | Issuer + audience checked explicitly. Both paths exercised: rejection (401) and accept, with a real **ES256** token |
| `POST /v1/saves` | **real, verified** | `202` with a real JWT; row committed and readable. Exercises `@CurrentUser`, the lazy profile upsert, and the JSONB mapping |
| `GET /v1/saves`, `/{id}` | **real, verified** | `200` on both; `/{id}` returns the created save, the list returns it too |
| Job queue | **real, verified** | Enqueue runs as part of the create path; the runner drains it |
| Job runner | **real, verified** | `SKIP LOCKED` claim, per-group exclusion, three error paths, stale-claim sweep. Claimed and completed a real job against live Supabase |
| Extraction cascade — metadata + captions | **real, unverified against yt-dlp** | `--dump-single-json` probe then `--write-auto-subs`, VTT to prose, error classification. Neither yt-dlp nor ffmpeg is installed on the dev machine, so the binary calls have never run |
| Extraction cascade — ASR | **absent** | ffmpeg → 16 kHz mono → Groq Whisper. A different provider with its own key |
| `process_save` handler | **real** | Runs the cascade and parks the text in `save_stages`. Saves stay `processing` — the Phase 3 model call is what advances them |
| Any Gemini call | **absent** | Phase 3. `gemini_calls` and `ai_budget_days` tables exist and are empty |
| OCR tier | **absent** | Phase 4 |
| Search (FTS + vector) | **schema only** | `search_tsv` and `embedding` columns exist and are populated by nobody. Phase 5 |
| RevenueCat / entitlements | **schema only** | `subscriptions`, `usage_counters` tables exist. Phase 5 |
| Expo app — theme & personalisation | **real, bundles clean** | 78 palette combinations, all audited for WCAG AA. Preferences persist |
| Expo app — auth + save create/list | **real, never run on a device** | Supabase email/password, `POST`/`GET /v1/saves`, all four feed states. Typechecks and bundles; no dev build exists yet |
| Expo app — Library / Spaces / digest | **sample content** | Need pipeline output or collaboration endpoints that do not exist |
| Expo app — share extension | **absent** | The *Open app when saving* toggle exists; the native extension does not. See [app/README.md](app/README.md) |

**Nothing is faked.** Every "real" row above is genuinely implemented — there are
no mock responses or placeholder implementations in the codebase.

### Verified on 2026-07-30

Against the live Supabase project: `V1` migrated (11.7s) · app started · Flyway
on the session pooler and Hikari on the transaction pooler, confirmed distinct
in the logs · `GET /actuator/health` 200 · unauthenticated and
malformed-token requests rejected 401 · `./mvnw clean verify` green, 56/56 tests.

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

**Unverified because the tooling is missing locally**

- **yt-dlp and ffmpeg are not installed on the dev machine.** The extraction
  cascade is written and its pure logic is well covered — VTT parsing, error
  classification, and the ordering decisions all have tests — but no yt-dlp
  process has ever been spawned. The `ExternalProcess` wrapper underneath it *is*
  verified against real child processes, including the pipe-deadlock and
  hang-timeout cases. Installing yt-dlp locally is what closes this.
- **The container must install both.** A plain JRE base image has neither, and
  the failure mode is every save retrying until it exhausts `max_attempts`.

**Blocking the Phase 1 exit**

- **Nothing in the app has run on a device.** The Expo app typechecks and
  bundles; no dev build exists, so no screen has been seen rendered and no
  request has left a device. Sign-in, the feed and save creation are all written
  but unproven — `npx expo run:android` is the next real test, and it is the
  remaining Phase 1 exit criterion alongside the store records.
- **Local setup is incomplete.** `app/.env` does not exist yet (copy
  `app/.env.example`), and the root `.env` predates `SUPABASE_ANON_KEY`, so the
  backend smoke test cannot mint a token either. Both are one-line fixes, but
  nothing works until they are done.

**Correctness**

- **Request-level idempotency on `POST /v1/saves`.** The job enqueue is
  idempotent by save id, but the save itself is not, so a retry creates a
  duplicate. This now has *two* callers: the iOS share extension's background
  `URLSession` (Phase 2) and the in-app Paste Link tile, which will double-post
  if a user taps twice on a slow network. Fix before silent capture ships.
- **No integration tests against a real database.** Unit tests cover validation
  and enum mapping only. Testcontainers with a `pgvector/pgvector` image is the
  natural next step.
- **`SecurityConfig` accepts `{ES256}` and nothing else.** `jwsAlgorithm()` adds
  to a set rather than extending the defaults. Rotating the Supabase signing key
  to RSA 2048 would break auth entirely, with the same misleading "no matching
  key(s) found". One extra line fixes it; see the gotcha above.

**Deferred by design, but easy to mistake for bugs**

- **A save never leaves `processing`.** The job runner now claims and completes
  the job, but its handler is a stub: it records an `accepted` stage and stops,
  because nothing yet fetches captions, metadata or audio. The app deliberately
  does not poll — it would spin without observing a transition — so a freshly
  created save keeps its "Processing" pill. Both halves are intentional; the
  cascade is what changes it.
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
