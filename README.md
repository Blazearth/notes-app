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
| Supabase JWT auth | **real, partly verified** | Issuer + audience checked explicitly. Rejection path verified (401); the accept path has not run |
| `POST /v1/saves` | **real, unverified** | Writes a row, enqueues a job, returns `202`. Never exercised with a valid token |
| `GET /v1/saves`, `/{id}` | **real, unverified** | Same |
| Job queue | **enqueue only** | Rows land in `jobs`. Nothing consumes them yet |
| Job runner | **absent** | Phase 2 — `SKIP LOCKED` claim, retry, `group_id` round-robin |
| Text-extraction cascade | **absent** | Phase 2 — yt-dlp / ffmpeg / ASR |
| Any Gemini call | **absent** | Phase 3. `gemini_calls` and `ai_budget_days` tables exist and are empty |
| OCR tier | **absent** | Phase 4 |
| Search (FTS + vector) | **schema only** | `search_tsv` and `embedding` columns exist and are populated by nobody. Phase 5 |
| RevenueCat / entitlements | **schema only** | `subscriptions`, `usage_counters` tables exist. Phase 5 |
| Expo app — theme & personalisation | **real, bundles clean** | 78 palette combinations, all audited for WCAG AA. Preferences persist |
| Expo app — Home / Library / Spaces / Capture | **real UI, sample content** | Built from the Claude Design mockups. Nothing calls the API yet |
| Expo app — auth, API calls, share extension | **absent** | The *Open app when saving* toggle exists; the native extension does not. See [app/README.md](app/README.md) |

**Nothing is faked.** Every "real" row above is genuinely implemented — the
unverified ones simply have not been run against a live token yet. There are no
mock responses or placeholder implementations in the codebase.

### Verified on 2026-07-30

Against the live Supabase project: `V1` migrated (11.7s) · app started · Flyway
on the session pooler and Hikari on the transaction pooler, confirmed distinct
in the logs · `GET /actuator/health` 200 · unauthenticated and
malformed-token requests rejected 401 · `./mvnw clean verify` green, 11/11 tests.

Expo app: `tsc --noEmit` clean · `expo export --platform android` bundles (3.8 MB
Hermes bytecode, every route resolved) · all 78 palette combinations audited for
WCAG AA contrast on body text, muted text, the FAB glyph, the digest label and
both nav-pill states — worst case 4.50:1. **Not run on a device or emulator**:
there is no dev build yet, so nothing here has been seen rendered.

**Not yet verified:** creating a save with a real Supabase JWT. That path
exercises the `@CurrentUser` resolver, the lazy profile upsert, and the JSONB
mapping — none of which have executed. It is the last Phase 1 exit criterion.

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
cp .env.example .env          # then fill in the two DB passwords
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

Grab an access token by signing in with the Supabase client (or from the
Supabase dashboard's API docs), then:

```bash
curl -X POST http://localhost:8080/v1/saves \
  -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"url","sourceUrl":"https://www.youtube.com/shorts/abc123"}'
```

Expect `202` with a body containing `"status":"processing"` and a `Location`
header. A row appears in `saves`, and a matching row in `jobs` waiting for the
Phase 2 runner.

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

- **Request-level idempotency on `POST /v1/saves`.** The job enqueue is
  idempotent, but a background `URLSession` retry from the iOS share extension
  will currently create a second save. This pairs with the extension work in
  Phase 2 — fix it there, before silent capture ships.
- **No integration tests against a real database.** Unit tests cover validation
  and enum mapping only. Testcontainers with a `pgvector/pgvector` image is the
  natural next step.
