# Feature status

**As of 2026-08-01, day 1 of the Shipaton build window (Aug 1 – Sep 30).**

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
| 🔵 **Mock-only** | Works in the app, but no backend serves it |
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
| Idempotent create | ✅ Done | Verified live, including a concurrent-retry race. No caller sends the header yet — it exists for the unbuilt iOS extension |

**Nine of the twelve capture tiles do nothing.** Only Paste Link acts.

---

## Extraction — getting text out of a source

Platform coverage is the gap most likely to be mistaken for done. The cascade is
verified; it is verified **against YouTube**.

| Feature | Status | What's missing |
|---|---|---|
| YouTube — captions + metadata | ✅ Done | Run against yt-dlp 2026.07.04 and a live video. Found and fixed three real defects |
| **Instagram Reels** | 🟠 Unverified | Never run against a real Reel. Instagram increasingly **requires authentication**, and cookies risk a ban and sit badly against the ToS posture. Unauthenticated failure may be the permanent outcome — this needs measuring, not assuming |
| **TikTok** | 🟠 Unverified | Same cascade, never exercised against a real TikTok URL |
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
| Model routing + budget guard | ✅ Done | Primary → fallback, daily counter persisted |
| Knowledge-type registry | ✅ Done | Adding a type is a data change |
| Enrichment (TMDB / Google Places) | 🟠 Unverified | 26 tests, all mocked. **No real TMDB or Places key has ever been used** |
| Embeddings | ✅ Done | 1536-d, normalised client-side |
| Real Gemini RPD | ⛔ Unknown | Still unverified. 250 vs 500 halves capacity and moves the paid-tier switch earlier |

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
| **AI groups** | 🔵 Mock-only | **No endpoint serves groups.** `apiRepository.listGroups()` returns `[]`, so with the real backend the section does not appear at all. Nothing generates groups — there is no clustering, no AI, no persistence |
| **Subgroups / nesting** | 🔵 Mock-only | The recursive model, the card previews and the detail screen exist and work against fixtures. Same gap: no backend, no generator, and never on a device |
| Group detail screen | 🔵 Mock-only | Verified in Chrome only |
| Spaces — CRUD, roles, invites | ✅ Done | Authorisation verified live, including 404-vs-403 |
| Spaces — comments, votes, activity | ✅ Done | Verified live |
| Spaces — duplicate detection | 🟠 Unverified | The 0.15 threshold is a guess; no two real saves compared |
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
| **RevenueCat SDK in the app** | ⛔ Not built | No `react-native-purchases` dependency at all |
| **Paywall** | ⛔ Not built | — |
| **Store products / subscriptions** | ⛔ Not built | — |
| Sandbox purchase | ⛔ Not built | — |

**The server half is done and the client half does not exist.** This is a
RevenueCat hackathon; this is a submission requirement, not a feature.

---

## Notifications & digests

| Feature | Status | What's missing |
|---|---|---|
| Push notification on `ready` | ⛔ Not built | No `expo-notifications`, nothing server-side. **This matters more than it looks:** the app deliberately does not poll, because the notification is meant to be the signal. Without it a save shows "Processing" until you pull to refresh |
| Weekly digest | ⛔ Not built | The last sample content in the app. No endpoint, no generator |
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

## Infrastructure

| Feature | Status | What's missing |
|---|---|---|
| Schema + migrations | ✅ Done | Applied against Supabase PG 17.6 |
| Auth (Supabase JWT, ES256) | ✅ Done | Both accept and reject paths |
| Job queue + runner | ✅ Done | Claim, exclusion, three error paths, stale sweep |
| Deployment (Render + Docker) | 🟠 Unverified | Config just landed. The image **must** install `yt-dlp`, `ffmpeg`, `tesseract` *and* `tesseract-ocr-eng` — a plain JRE has none, and missing language data reads every frame as nothing, silently |
| Keep-warm cron | 🟠 Unverified | Supabase pauses after ~7 days idle; a paused project during judging is a demo-day failure |
| Integration tests (Testcontainers) | ⛔ Not built | Unit tests cover validation and enum mapping only |
| Store records / Apple enrolment | ⛔ Not built | Phase 0, never closed. Not code, and the lead time is external |

---

## Known defects

| Defect | Impact |
|---|---|
| `Idempotency-Key` race recovery re-reads inside an aborted transaction | Would fail rather than return the existing save. Never observed — the pre-check catches every non-concurrent replay |
| `SecurityConfig` accepts ES256 only | Rotating the Supabase key to RSA breaks auth entirely, with a misleading "no matching key(s) found". One line |
| Android share worker can hold a stale access token | A silently dropped share, after the user already saw "Saved" |
| Capture failure after dismissal has no surface | Warns to console; the save never appears. Needs a toast |

---

## Summary

**21 done, 17 unverified, 4 mock-only, 23 not built.** Under a looser bar the
first two columns would merge and this would read as 38 of 65 — which is
roughly how it feels while writing it, and roughly twice what has actually been
proven.


Done and verified: the **backend spine** — schema, auth, saves, the job runner,
YouTube caption extraction, the Gemini classify call, embeddings, hybrid search,
the shopping-list Act, Spaces, and the RevenueCat webhook.

Not done: **the entire client half of monetisation**, **the iOS share extension**
(the core promise), **push notifications**, **AI groups and subgroups**
(mock-only, nothing generates them), **eleven of twelve capture tiles**, **three
of four Acts**, and **verification of nearly everything on a device** — plus
Instagram and TikTok extraction, which is what most users would actually be
sharing.
