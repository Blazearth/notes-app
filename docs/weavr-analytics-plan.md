# Weavr PostHog Analytics Plan

*Grounded in `CLAUDE.md`, the actual route/screen structure in `app/app/`, and the pipeline described in `api/`. Where the codebase disagrees with a generic analytics taxonomy, the codebase wins — call-outs below flag every place this plan diverges from "the obvious list."*

---

## A. Analytics philosophy

Weavr's loop is **Capture → Extract → Summarize → Organize → Find/Act**. Analytics exists to answer whether that loop actually delivers value to a real person, not to produce a complete log of everything technically observable.

Three rules govern every decision below:

1. **An event must answer one of the seven questions in the brief, or it doesn't ship.** "We could track this" is not a reason to track this.
2. **Server truth beats client-reported truth wherever both exist.** Weavr already treats the job queue and the RevenueCat webhook as the source of truth for save status and entitlement — analytics should key off the same source, not a client's optimistic guess.
3. **Never let an analytics property become a second copy of user content.** The privacy policy currently promises zero event-tracking; the moment that changes, the promise that survives is "behavioral metadata only," never "the content, tagged."

## B. Product questions

Carried over from the brief, unchanged — these are good questions and don't need editing, only events that actually answer them (see E–L).

## C. North Star metric candidates

**There isn't enough evidence yet to lock one in, and pretending otherwise would just mean picking one before the data exists to justify it.** Three candidates, in order of how soon they'll be trustworthy:

| Candidate | What it measures | Why it's a candidate | Why it might not survive |
|---|---|---|---|
| **Week-1 second-capture rate** — % of newly activated users who make a second save within 7 days of their first | Habit formation, fastest | Cheapest to trust with small post-launch volume; a sharper early signal than D7 return because it isolates *capturing again* from *just opening the app* | Says nothing about whether saves are ever retrieved — Weavr could win this metric and still be a graveyard |
| **Saves revisited per active user per week** (`save_viewed` where `days_since_capture > 1`) | Whether Extract→Organize→Find is delivering, not just Capture | Directly tests the philosophy's second half, which is where read-later tools historically fail | Needs weeks of retained users before it's non-zero enough to read |
| **Weekly successful captures per retained user** | Combines quality + volume in one number | Good exec-summary metric once mature | Too composite to diagnose *why* it moved — not a great day-to-day steering metric |

**Recommendation:** use **Week-1 second-capture rate** as the working North Star for the first 60–90 days post-launch — it's computable almost immediately and it's the sharpest available proxy for "the loop worked once, will they trust it again." Revisit once there's enough volume to check whether it actually predicts D30 retention; if it doesn't, saves-revisited is the fallback.

## D. Activation definition

- **Activation event:** `save_ready` with `is_first_save = true` — the user's first capture actually reaching a usable result. Not `sign_in_completed` (no value delivered yet) and not `capture_received` (a capture that fails extraction never delivers the "aha" moment — counting it as activation would hide pipeline failures inside a vanity metric).
- **Activation window:** 24 hours from `sign_in_completed`. The pipeline's median time-to-ready should be minutes; 24h is generous enough to absorb a busy-queue day without falsely failing someone, tight enough that a multi-day gap is a real problem, not activation.
- **Activation rate:** % of new `sign_in_completed` users reaching `save_ready(is_first_save)` inside the window.
- **Funnel drop-off points to watch, in order:**
  1. `sign_in_completed` → `capture_received` — did they ever try to save anything at all (onboarding/discovery problem, not a pipeline problem)
  2. `capture_received` → `extraction_completed` — pipeline reliability (this is where OCR/ASR/yt-dlp failures show up)
  3. `extraction_completed` → `save_ready` — should be ~100%; any real gap here is a bug, not a product question
  4. `save_ready` → `save_viewed` — did they ever open what they saved (tests whether the "ready" notification actually closes the loop)
- **Time-to-value:** median and p90 of `sign_in_completed` → `save_ready(is_first_save)`.

## E. Event taxonomy

**The single most important architectural fact for this taxonomy: most captures never run through the RN JS layer at all.** Android's silent share-intent receiver enqueues into `CaptureStore` (SQLite) and uploads via WorkManager while the app stays backgrounded and PostHog's React Native SDK is not running. A client-only instrumentation plan would simply miss the majority of captures. So the capture/extraction/save side of this taxonomy is **emitted server-side** (Spring Boot, keyed by the authenticated user id, at the point each stage's state already changes in Postgres) — reusing state the pipeline already tracks rather than adding new work. Screen/search/Act/Space events are genuinely client-only and use the RN SDK normally.

Two corrections to the brief's draft list, made explicit because they matter:

- **No `onboarding_completed` event** — there is no onboarding flow in the app (`sign-in.tsx` → the Home/Library/Spaces tab shell directly). Inventing the event to match a generic funnel shape would track a step that doesn't exist. Home's real empty state (`saves.length === 0`) is the de facto first-run moment; it gets its own event only if it turns out to matter (Phase 2).
- **No `collection_created` for the personal library** — per `CLAUDE.md`, groups/collections there are fully derived on-device from the synced library, never a user action. A "did they organize their stuff" question only has a real answer inside **Spaces**, where pins and shared collections are genuinely user-created. The taxonomy below reflects that split.

| Event | Fires when | Emitted by | Why | Priority |
|---|---|---|---|---|
| `app_opened` | App reaches foreground (cold or warm) | Client | Baseline reach/frequency; PostHog derives session/weekly-active from this without a separate `session_open` event — the brief's draft list double-counts that | Essential |
| `sign_in_completed` | Supabase session established | Client, `identify()` fires in the same handler | Anchors the activation window and every retention cohort | Essential |
| `capture_received` | API accepts an upload (share-intent or in-app) and enqueues a job | **Server** | The true "a capture happened" signal — works identically for silent share-intent and the in-app sheet, which a client event cannot | Essential |
| `capture_failed` | Upload rejected pre-pipeline (bad URL, unsupported platform, storage error) | Server | Distinguishes "we never got it" from "we got it and extraction failed" — different fixes | Essential |
| `extraction_completed` | Job reaches `ready` | Server | Pipeline quality — reuses `knowledge_type`/`model_used`/`confidence`/`escalated`/`latency_ms` the pipeline already computes | Essential |
| `extraction_failed` | Job reaches `failed` | Server | Where/why the cascade broke, by stage and source type | Essential |
| `save_ready` | Job's `status` flips to `ready` (fired alongside, or folded into, `extraction_completed` — see note below) | Server | The activation/value-delivered moment; carries `is_first_save` and `time_to_ready_ms` | Essential |
| `save_viewed` | Save-detail screen opened | Client | Tests whether "ready" ever gets acted on; `days_since_capture` bucketed, not raw | Essential |
| `save_deleted` | Save removed | Client or server | Bucketed `age_since_capture` (`<1h`/`<1d`/`<1w`/`>1w`) — fast deletion proxies bad extraction, slow deletion is normal cleanup | Essential |
| `save_field_edited` | User corrects an extracted field | Client | Trust/quality signal — **not buildable yet**; field-editing is still on the P0 backlog per the changelog. Add the event when the feature ships, not before. | Optional (Phase 3) |
| `search_performed` | Search executed | Client | `result_count`, `has_results` — **never the query string** | Essential |
| `search_result_opened` | A result tapped | Client | `position`, `knowledge_type` | Essential |
| `act_started` | User opens an Act (shopping list, compare-workouts) | Client | Tests whether Find/Act — the philosophy's last mile — gets used at all | Essential |
| `act_completed` | Act finished (list checked off, comparison viewed) | Client | Distinguishes opened-it from used-it | Essential |
| `group_opened` / `collection_opened` | User drills into a derived knowledge-type group/collection in Library | Client | Whether derived organization is browsed — informational, not a funnel step | Optional (Phase 2) |
| `space_created` | User creates a Space | Client | Real, user-driven organization signal | Essential |
| `space_joined` | User joins via invite | Client | Same | Essential |
| `item_pinned_to_space` | Save pinned into a Space collection | Client or server | The Space-level analogue of "did they organize anything" | Optional (Phase 3) |
| `screen_viewed` | Navigation to a screen with no more specific event | Client, one generic event with `screen_name` | Covers Home/Library/Spaces/Search/Paywall/Settings only — see §G | Essential (scoped) |
| `paywall_viewed` | Paywall screen shown | Client | `trigger` (entitlement_gate/settings) | Essential |
| `purchase_started` | RevenueCat purchase flow invoked | Client | `package_id` | Essential |
| `purchase_confirmed` | RevenueCat webhook lands, `subscriptions` row updated | **Server** | Server truth, not the client's optimistic poll result — mirrors the existing "only the server's answer is rendered" design already built into the paywall | Essential |
| `purchase_poll_timeout` | Client's bounded ~21s poll expires before confirmation | Client | Quantifies how often users hit the uncomfortable "still confirming" state that's already an explicit product decision | Essential |

**Note on `extraction_completed` vs `save_ready`:** these will usually be the same moment in the current pipeline (step 5 sets `status = ready` right after extraction). Keep them as two events anyway — `extraction_completed` is a pipeline-quality event (fires on every job, success framing lives in `extraction_failed`'s absence) and `save_ready` is a product/activation event (only meaningful with `is_first_save` attached). Collapsing them would force the activation funnel to filter a generic pipeline event by a property that doesn't belong on it.

## F. User identity strategy

- **`distinct_id` is the Supabase user id (`sub` claim)** on both sides — the same id every service in `api/` already keys authorization off of. This is what lets one PostHog "person" merge client and server events without extra plumbing.
- **Client, pre-auth:** `index.tsx` redirects straight to `/sign-in` when there's no session, so the anonymous window is just the sign-in screen itself — thin, but real (a cold `app_opened` can fire before a session exists). Use PostHog's anonymous `distinct_id` for that window, then call `posthog.identify(supabaseUserId)` the moment `SessionProvider` establishes a session. PostHog's standard anonymous→identified merge handles the rest; no custom alias logic needed.
- **Client, sign-out:** call `posthog.reset()` in the sign-out handler. Skipping this is the single most common way multi-account devices end up with mismerged people in PostHog — put it in the QA checklist (§Q).
- **Server:** always authenticated by the time a job exists, so there's no anonymous concept server-side — every server-emitted event just uses the save's/job's owning `user_id` directly.
- **Reinstall / multiple devices:** calling `identify()` with the same Supabase id on every device, every session restore (not just first login), naturally merges all devices into one person. No dedicated "device changed" event needed.

## G. Privacy and security

**Should collect:** feature usage, screen/flow navigation, success/failure and error *type* (not error message text if the message could echo content), timing/latency, `knowledge_type`, `model_used`, `confidence`, `escalated`, app version, platform, coarse buckets (age-since-capture, result counts).

**Should never collect:** raw extracted text, save titles or thumbnails, source URLs (query params can carry tokens), search query strings, Space comment text, shopping-list item text, workout exercise names/notes, auth tokens, anything from a captured PDF/screenshot/photo, per-frame OCR or ffmpeg internals (that's a pipeline debug concern for logs/APM, not a product event).

**How to make "accidentally sent" structurally impossible, not just a code-review hope:** define every event's property shape as a typed interface with only enum-like/numeric/boolean fields — no free-text field exists to misuse. §O below makes this the actual architecture, not a guideline someone has to remember.

**This plan requires a policy update before it ships, not after.** `legal/privacy.html` currently states *"Weavr contains no analytics SDK, no advertising SDK, and no third-party crash-reporting SDK... there is no event-tracking pipeline in the app."* Adding PostHog falsifies that live claim. Updating that page, plus Play's Data Safety form, is a **Phase 1 blocker**, not cleanup.

## H. Error tracking

| Event | What failed | Where | Frequency lens | Reproducibility |
|---|---|---|---|---|
| `capture_failed` | Pre-pipeline rejection | `source_type`, `error_type` (unsupported_url/auth_required/timeout/storage_error), `stage` | Rate by `source_type` and app version | `error_type` + platform is usually enough; never attach the URL itself |
| `extraction_failed` | Cascade failure | `source_type`, `stage` (download/asr/ocr/classify), `error_type`, `latency_ms` | Rate by stage — tells you if it's yt-dlp breakage vs. OCR confidence floor vs. Gemini quota | Stage + error_type; the actual failing frame/transcript stays server-side in logs, not in PostHog |
| `save_failed` | Job never resolves (stuck/crashed) | `stage`, `retry_count` | Should be near-zero; any nonzero rate is a bug ticket, not a metric to "watch" | App version + stage |
| `search_failed` | Search request errors | `error_type` | Rare; mostly infra | App version |

`app_crashed` deliberately isn't a hand-rolled PostHog event — see §21.

## I. Performance tracking

Reuse latency fields the pipeline already computes rather than inventing new instrumentation:

- `extraction_completed.latency_ms`, `save_ready.time_to_ready_ms` — already free from existing pipeline state.
- `app_opened.startup_duration_ms` — the one cold-start number worth its own field, because first impression is make-or-break for a silent-capture product with no other feedback loop.
- `search_performed.latency_ms` — the one interactive-latency number worth tracking, since search is a named product question ("do users search").

**Not tracked:** per-screen load time for every route, per-API-call latency as an analytics event (that's APM/logs), per-frame OCR timing. Performance analytics answers "does this affect UX or diagnose a real problem" — most of the pipeline's internals already have a home in server logs.

## J. Funnels

Selective, matching the brief's own instruction not to build dashboards just because PostHog allows it.

**Build at launch:**
1. **Activation:** `sign_in_completed` → `capture_received` → `extraction_completed` → `save_ready` (first)
2. **Capture reliability** (ongoing, not just launch): `capture_received` → `extraction_completed` / `extraction_failed`
3. **Retrieval:** `search_performed` → `search_result_opened`

**Defer to Phase 2/3:** a Space-adoption funnel (`space_created`/`space_joined` → `item_pinned_to_space`) — Spaces are a secondary surface; the personal capture→retrieval loop is what needs proving first, consistent with the existing go-to-market playbook's own call to deprioritize Spaces polish.

**Explicitly dropped from the brief's draft list:** the "Organization funnel" as originally framed (`item_saved → collection_created → item_added_to_collection`) doesn't map to anything real — personal-library organization isn't a user action to fund a funnel.

## K. Cohorts

| Cohort | Definition |
|---|---|
| **Activated** | Has `save_ready(is_first_save=true)` |
| **Power users** | ≥3 `capture_received` in trailing 7 days (rolling PostHog cohort) |
| **One-and-done** | Exactly one lifetime `save_ready`, no `app_opened` or `save_ready` in the last 14 days |
| **Retrievers** | ≥2 `search_performed` in trailing 7 days |
| **Space organizers** *(renamed from "Organizers")* | ≥1 `space_created` or `item_pinned_to_space` — the personal library has no manual organizing action, so this cohort has to live in Spaces to mean anything real |
| **D7 / D30 retained** | Standard PostHog retention cohort off `app_opened`, not a custom definition |

## L. Retention analysis

Segment D1/D7/D30 by: completed first capture (activated vs. not), saved 1 vs. 3+ items, searched at all, revisited an existing save. **Don't assume the answer** — this is explicitly a discovery exercise, not a confirmation of a pre-decided story. Cross-reference against the existing pilot lesson already on file: 12 people over 14 days wasn't enough to trust a retention percentage; the same caution applies to the first weeks of real launch data (see §16).

## M. Launch dashboard

**Acquisition:** new `sign_in_completed`, platform split. Be honest about a real gap: PostHog alone on Android can't attribute *how* someone discovered Weavr without wiring Play's install-referrer API — don't imply this section covers acquisition channel if that integration isn't built.

**Activation:** activation rate, time-to-value (median/p90), funnel drop-off by step.

**Engagement:** captures/user/week, `search_performed` rate, `act_completed` rate, Space adoption rate.

**Retention:** D1/D7/D30, activated-only retention, Week-1 second-capture rate (the working North Star).

**Quality:** `extraction_failed` rate by source type, `capture_failed` rate, `save_ready` time-to-ready distribution.

**Product health:** biggest funnel drop-off step, top `error_type`s, feature adoption (search / Acts / Spaces as % of activated users).

## N. Experimentation framework

**Be opinionated: don't run formal PostHog experiments at launch.** Weavr's own system ceiling (500 Gemini RPD → roughly 400 saves/day across *all* users) plus a fresh Play listing means weekly activation volume will be far too low for months to detect the effect sizes UI experiments realistically produce. Running an underpowered A/B test doesn't de-risk a decision, it just adds noise and false confidence.

**Until volume supports power (rule of thumb: low thousands of weekly activations):** ship changes sequentially and read the funnel before/after, rather than randomizing.

**Candidates worth testing later, once volume exists** — each needs a primary metric, a guardrail, and a real hypothesis, not just "try both":
- Home empty-state copy → primary: time-to-first-capture; guardrail: doesn't increase paywall_viewed for non-converters
- Paywall trigger timing → primary: purchase_started rate; guardrail: activation rate unaffected
- Capture confirmation toast wording → primary: `save_viewed` rate within 24h; guardrail: none needed, low risk

## O. Code architecture

**`app/src/analytics/`**
- `events.ts` — one exported const per event name plus a discriminated union type mapping event → property shape. `track()` is fully typed against this union, so a free-text property literally cannot compile in.
- `client.ts` — thin wrapper over `posthog-react-native`: `init`, `identify`, `reset` (wired into `SessionProvider`'s sign-in/sign-out handlers), a single typed `track()`. No-ops (console-logs instead) when `__DEV__` unless an explicit debug env var is set.

**`api/src/main/java/com/weavr/api/analytics/`**
- `AnalyticsService.java` — wraps PostHog's server capture endpoint via `RestClient`, the same pattern already used for Gemini/Groq clients (remember `spring-boot-starter-restclient` per `CLAUDE.md`). One method: `capture(String distinctId, String event, Map<String,Object> properties)`, called async/fire-and-forget so a PostHog outage can never fail a save — same resilience posture already applied to enrichment.
- `AnalyticsEvents.java` — event-name constants, kept in sync with `app/src/analytics/events.ts` by convention (small team; not worth codegen for ~20 events).
- Call sites: `ProcessSaveHandler`/`ClassifySaveHandler` (capture_received, extraction_completed/failed, save_ready), the upload-accepting controller (capture_received/capture_failed), the RevenueCat webhook handler (purchase_confirmed).

## P. Development / staging / production

- Separate PostHog project keys for dev vs. prod (`EXPO_PUBLIC_POSTHOG_KEY`, `POSTHOG_API_KEY` on Render) — this is exactly the `EXPO_PUBLIC_*`-inlined-at-build-time trap `docs/testing.md` already documents for other keys; get it right the same way.
- `weavr.analytics.enabled` Spring property, false locally, true only on Render.
- App: PostHog disabled under `__DEV__` unless an explicit debug flag is set, logging to console instead — otherwise every emulator/Expo-web dev session pollutes production data.

## Q. QA checklist

- Each essential event fires exactly once per real occurrence (watch for double-fire on retry/re-render)
- Fires at the correct pipeline/UI moment, not early or late
- Properties match the typed shape — no drift between `events.ts`/`AnalyticsEvents.java`
- No property contains raw content, a URL, or query text (spot-check payloads, don't just trust the type system)
- **Share a link with the app fully closed and confirm `capture_received` appears without the app ever opening** — the easiest thing to get wrong here is assuming the client SDK "just handles" the primary capture path; it structurally cannot, since JS never runs during a silent share
- Failed operations produce the matching `*_failed` event, not silence
- Restarting the app doesn't replay `app_opened` for an already-open session
- `posthog.reset()` fires on sign-out; a second account signing in on the same device doesn't inherit the previous person
- Dev/staging runs never appear in the production PostHog project

## R. What NOT to track

- Raw save titles, extracted text, thumbnails, source URLs, search query strings, Space comment text, shopping-list item text, workout exercise names/notes — anything that echoes captured content
- Per-frame OCR/ffmpeg internals — pipeline debug data belongs in logs/APM, not product analytics
- A `screen_viewed` event for every one of the ~19 routes — skip `appearance.tsx`, `auth/callback.tsx`, and any screen already covered by a more specific event (`save_viewed`, `group_opened`) rather than double-counting the same navigation two ways
- Every frame of the Home/Library/Spaces pan-gesture animation — that's 60fps UI mechanics, not a product signal
- A separate event per settings toggle — bundle into one `settings_changed{setting_name,value}` for the handful that matter (`open_app_on_save`, notification prefs), not theme/appearance micro-adjustments
- Hand-rolled `app_crashed` from a JS `try/catch` — it only fires if the JS thread survives long enough to catch its own death, which understates exactly the crashes worth knowing about. Pair with a real crash reporter (Sentry or similar) instead of faking reliability data in PostHog.
- Any "collection_created" analogue for the personal library — it isn't a user action there
- Session replay — Weavr's content is personal screenshots/photos/PDFs; replay would visually capture exactly what the privacy policy commits to never collecting

## S. Implementation priority

### Phase 1 — must implement before launch
- **Blocking, first:** update `legal/privacy.html` and the Play Data Safety form to reflect PostHog
- `app/src/analytics/{events.ts,client.ts}`; `identify`/`reset` wired into `SessionProvider`
- `api/.../analytics/{AnalyticsService,AnalyticsEvents}.java`
- Events: `app_opened`, `sign_in_completed`, `capture_received`, `capture_failed`, `extraction_completed`, `extraction_failed`, `save_ready`, `save_viewed`, `save_deleted`, `search_performed`, `search_result_opened`, `act_started`, `act_completed`, `paywall_viewed`, `purchase_started`, `purchase_confirmed`, `purchase_poll_timeout`, `screen_viewed` (Home/Library/Spaces/Search/Paywall/Settings)
- Activation funnel + capture-reliability funnel
- Dev/prod key separation

### Phase 2 — first 2 weeks
- `group_opened`, `collection_opened`, `space_created`, `space_joined`
- Retrieval funnel; cohorts (activated/power/one-and-done/retrievers)
- D1/D7/D30 retention report
- A real crash reporter, separate from PostHog

### Phase 3 — after meaningful usage volume
- `save_field_edited` (once field-editing ships), `item_pinned_to_space`, Space-adoption funnel
- Feature-adoption-vs-retention correlation analysis
- North Star lock-in decision (§C)
- First real experiments — only once weekly-activation volume can power them

### Don't implement yet
- A/B testing / feature flags (no volume to power a result)
- Per-button UI interaction tracking
- Native pre-upload "share_intent_captured" instrumentation (queue-lag signal, not needed for the core funnel)
- Session replay
- Any manual "organize your personal library" event — the product doesn't have that action
