# Google Play release checklist — Weavr

Written 2026-08-13, against the repo as it stands. Split into **blockers found in this
codebase** (concrete, with file references), then the Play Console work, then the things
that only exist outside the repo.

Nothing here is generic advice that doesn't apply — every item in §1 was verified against
the actual files.

---

## 1. Blockers in this repo

### 1.1 The production build ships with no configuration at all — highest priority

[app/eas.json](../app/eas.json) gives `preview` an `env` block with all three
`EXPO_PUBLIC_*` values, and gives `production` **none**:

```jsonc
"preview":    { "env": { "EXPO_PUBLIC_SUPABASE_URL": "…", "EXPO_PUBLIC_SUPABASE_ANON_KEY": "…", "EXPO_PUBLIC_API_BASE_URL": "…" } },
"production": { "android": { "buildType": "app-bundle" } }        // ← no env
```

`EXPO_PUBLIC_*` is **inlined into the JS bundle at build time**
([app/src/api/config.ts](../app/src/api/config.ts#L4)), so a production AAB built today
resolves all three to `''`, `MISSING_CONFIG` is non-empty, and **every user lands on
`ConfigErrorScreen` instead of the app**. It will build cleanly, upload cleanly, pass
review's install check only if the reviewer never opens it, and be broken for 100% of
real installs.

Fix: add the same `env` block to `production` (or move the values to EAS environment
variables scoped to the production profile, which is better — the anon key is safe to
publish, but keeping build config in one place isn't).

- [ ] `production` profile has `EXPO_PUBLIC_SUPABASE_URL`, `EXPO_PUBLIC_SUPABASE_ANON_KEY`, `EXPO_PUBLIC_API_BASE_URL`
- [ ] Verified by grepping the built bundle for the API host, not by assuming

### 1.2 No `versionCode`, and `appVersionSource: "local"`

[app/app.json](../app/app.json) has `"version": "0.1.0"` and no `android.versionCode`.
With `"appVersionSource": "local"` in eas.json, EAS reads the version from app.json rather
than managing it remotely — so every build is `versionCode 1`, and Play rejects the second
upload ("Version code 1 has already been used").

- [ ] Add `"versionCode": 1` under `android` in app.json, and bump it on every upload
- [ ] Decide `version` for launch — `0.1.0` is fine as a user-facing string, but pick it deliberately since it shows in the listing
- [ ] (Optional) switch to `"appVersionSource": "remote"` and let EAS auto-increment

### 1.3 `submit.production` is empty

No Play service-account key is configured, so `eas submit` can't upload.

- [ ] Create a Google Play service account (Play Console → Setup → API access), grant it release permissions, download the JSON
- [ ] Reference it from `submit.production.android.serviceAccountKeyPath`, and **gitignore the JSON** — it's a credential, and this repo has already leaked one credential to git once (`www.youtube.com_cookies.txt`, see CLAUDE.md). Add the ignore rule in the same commit as the key, not after.

### 1.4 The privacy policy is written; it still has to be filled in and hosted

**Written 2026-08-14.** [legal/privacy.html](../legal/privacy.html) is a complete v1.0
policy, audited against this codebase rather than templated — see
[legal/README.md](../legal/README.md). The Settings row is wired
([app/src/screens/SettingsScreen.tsx](../app/src/screens/SettingsScreen.tsx),
[app/src/legal/links.ts](../app/src/legal/links.ts)) and **hides itself until
`PRIVACY_POLICY_URL` is set**, so it can never be the no-op it used to be.

Play requires a **publicly hosted privacy policy URL** for every app, entered in the Play
Console *and* (for apps that collect data) reachable from inside the app. What remains:

- [ ] Fill in all 17 `[BRACKETED PLACEHOLDERS]` — legal entity, address, privacy email, grievance contact, effective date, minimum age, Supabase region
- [ ] Resolve the 7 **Legal review required** items — 5 are their own callouts, 2 are inline in the AI-tier and public-bucket notices (§4.1, §8)
- [ ] Host it — `.github/workflows/legal-pages.yml` publishes *only* `legal/` to Pages, so `docs/` stays private. **Pages on a private repo needs a paid GitHub plan**; otherwise use Cloudflare Pages / Netlify / any static host
- [ ] Set `PRIVACY_POLICY_URL` in `app/src/legal/links.ts` and confirm the Settings row appears
- [ ] Add a Terms document and row if you intend to have terms — §1 of the policy already references `[TERMS URL]`

The policy states things this app actually does that a template would have got wrong —
the Gemini free tier's model-improvement terms, the **public** screenshot bucket, the
JWT that outlives account deletion, and the absence of data export. See §4.

### 1.5 The backend is on free tiers that go to sleep

[render.yaml](../render.yaml) is `plan: free`, and Supabase is the free tier. Two
consequences for a reviewed, published app:

- **Render free spins down after ~15 minutes idle.** The first request after that takes
  tens of seconds. A reviewer opening the app cold sees a hang or a timeout, and the app's
  own error path is what they'll judge.
- **Supabase free pauses the project after ~7 days of inactivity** (CLAUDE.md flags this
  already). A paused project during review is a rejection.

- [ ] Either upgrade Render to a paid instance before submitting, or accept the cold start and make sure the app's loading/error states read as "connecting", not "broken"
- [ ] Keep-warm cron that issues a **real query**, not an HTTP ping to a static route
- [ ] `/actuator/health` confirmed reachable publicly

### 1.6 The app has never been run on a device — and that is the whole submission

This is the biggest risk and it isn't a config item. Per CLAUDE.md, essentially all app-side
verification is typecheck + web bundle + headless Chrome. Things that **cannot** have been
verified that way and that a reviewer will hit immediately:

- `sqliteStore.ts` — has never executed. app.json now sets `expo-sqlite`'s
  `enableFTS: true`, and the FTS path, the `MATCH` query and the bm25 ordering are reasoned
  about, not run.
- The Android share receiver / `CaptureStore` / `ShareRecovery` Kotlin — prebuild-verified
  and hand-reviewed, never compiled or run.
- `react-native-gesture-handler` swipe actions — explicitly unverified (synthetic CDP mouse
  events don't trigger `Pan`).
- Haptics, `useKeepAwake`, the rest timer, push/deep links.

- [ ] Build a **production-profile AAB** (not preview APK) and install it on a real device
- [ ] Sign up as a brand-new user and complete: sign in → share a link from another app → save reaches `ready` → search → Space → delete account
- [ ] Confirm the app works after force-stop and after a cold start with no network

### 1.7 RevenueCat is server-only — do not advertise a paid tier

`react-native-purchases` is **not** in [app/package.json](../app/package.json). The API has
`billing/` with a RevenueCat webhook, entitlement service and usage counters, but the app
has no purchase flow, no paywall, and no products.

That's a fine state to launch in — ship it free. But:

- [ ] The store listing must not describe a subscription, "Pro", or any paid feature
- [ ] "Contains ads" and "In-app purchases" both declared **No** in the console
- [ ] If you add purchases later, digital goods **must** use Google Play Billing — a Stripe/web checkout for in-app features is a policy violation and a common rejection

### 1.8 `USE_MOCK_DATA` — already safe, verify anyway

[app/src/data/config.ts](../app/src/data/config.ts) is `false`, and `.githooks/pre-commit`
forces it. Shipping it as `true` is a **silent** outage (the fixtures look healthy), so:

- [ ] Confirm `false` in the exact commit you build the release from

---

## 2. Play Console — account and release track

### 2.1 The 12-testers / 14-days requirement

If your developer account is a **personal/individual** account (not an organisation) opened
after Nov 2023, Google requires a **closed test with at least 12 testers opted in,
continuously, for 14 days** before you can apply for production access. Being identity-verified
is separate from and does not replace this.

This is a two-week calendar dependency, so it's the first thing to start, before any of the
polish above.

- [ ] Check your account type in Play Console → Setup → Advanced settings
- [ ] If individual: create the closed test track, recruit 12 real testers (real accounts, must stay opted in), start the clock **now**
- [ ] Only after 14 days: apply for production access

If it's an organisation account, this doesn't apply and you can go to production directly.

### 2.2 App setup

- [ ] App created with package `com.weavr.app` (matches app.json — this is permanent, it cannot be changed after the first upload)
- [ ] App category, contact email, external marketing opt-out
- [ ] Play App Signing enrolled (default; let EAS generate the upload keystore and **back it up** — losing it means you can't update the app)

---

## 3. Store listing assets — none of these exist in the repo

`app/assets/images/` has app icons only. Everything below has to be produced:

- [ ] **App icon** 512×512 PNG (can be derived from `icon.png` / `weavr-symbol.svg`)
- [ ] **Feature graphic** 1024×500 PNG — required, no exceptions
- [ ] **Phone screenshots** — minimum 2, realistically 4–8. Use real content, not mock-mode fixtures with invented data
- [ ] **Short description** ≤ 80 chars
- [ ] **Full description** ≤ 4000 chars
- [ ] App name — "Weavr" (the directory is `saveIt`; that is not the product name)

Screenshot content note: the app currently renders `[unclear]` sentinels for fields the
extraction couldn't fill. Screenshot saves that extracted cleanly.

---

## 4. Data safety form + policy content — the app-specific answers

This form is where Weavr differs from a generic app, and where a wrong answer is a policy
violation rather than a cosmetic mistake.

### 4.1 Gemini free tier uses prompts for model improvement

CLAUDE.md states it directly: free-tier prompts and responses are used to improve Google's
products. Weavr ingests users' screenshots, shared links, captions and PDFs — so this is
**third-party sharing for a purpose other than providing the service**, and "processed to
provide the app's functionality" is the wrong answer.

- [ ] Declare data sharing with a third party, purpose including model/product improvement
- [ ] Say so in the privacy policy in plain language
- [ ] Re-answer both if you move to a paid Gemini tier, where the terms differ

### 4.2 Everything the app collects

Walk the actual code, not memory. At minimum:

- [ ] **Email address** (Supabase auth) — collected, tied to identity, required for account
- [ ] **Photos** (`expo-image-picker`, screenshot capture → `POST /v1/saves/image` → Supabase Storage) — collected and transmitted
- [ ] **User-generated content** — saved links, typed notes, comments, Space activity
- [ ] **App activity / search history** — the search endpoint and embeddings
- [ ] Whether each is encrypted in transit (yes, HTTPS) and whether users can request deletion (yes, §4.3)

### 4.3 Account deletion — in-app exists, the web URL does not

Account deletion landed 2026-08-13 (`DELETE /v1/me`, Settings → Danger Zone), which
satisfies the in-app half. Play **also** requires a **publicly reachable web URL** where a
user can request account and data deletion without installing the app.

- [x] Publish a deletion-request page — [legal/privacy.html](../legal/privacy.html) §10 is written to serve as one: it covers the in-app route *and* an email route that works without installing the app, which is what Play actually requires. A second document would only drift from this one.
- [ ] Enter `<policy URL>#account-deletion` in Play Console → App content → Data deletion
- [ ] Be accurate about what's deleted vs retained. Note the known residual documented in
      CLAUDE.md: an already-issued JWT stays valid until it expires, because auth is
      stateless. Short window, but don't claim instant global session revocation.

### 4.4 Permissions declared in the manifest

Generate the manifest and read it rather than guessing — `expo-image-picker`, the share
receiver plugin and `expo-network` each contribute:

```bash
cd app && npx expo prebuild -p android --clean
# then read android/app/src/main/AndroidManifest.xml
```

- [ ] Every `uses-permission` is one you can justify in the listing
- [ ] No permission present that no code path uses (a stale one is a rejection reason)
- [ ] `ShareReceiverActivity`'s `android:exported="true"` is correct and intentional (it is — it receives `ACTION_SEND`)

### 4.5 Other App content declarations

- [ ] Content rating questionnaire completed (answer honestly about user-generated content and sharing between users — Spaces means users can see each other's content)
- [ ] Target audience: adults. **Do not** mark it as appealing to children — that triggers Families policy, which this app is not built for
- [ ] Ads: none
- [ ] News app: no
- [ ] COVID / financial features: no
- [ ] Government app: no

### 4.6 Content the pipeline downloads

The app ingests third-party media via `yt-dlp`. CLAUDE.md's own rule — only ingest content
the user themselves shares, no bulk scraping, no public rehosting — is the right posture and
should be reflected in the terms.

- [ ] Terms state that users are responsible for the content they share into the app
- [ ] Confirm no downloaded video is retained (the pipeline deletes in a `finally`; verify the Storage bucket holds only thumbnails and user screenshots)

---

## 5. Build and submit mechanics

```bash
cd app
npm run typecheck                              # must be clean
npx eas build --platform android --profile production
npx eas submit --platform android --profile production
```

- [ ] Target SDK meets Play's current minimum for new apps (Expo 57 / RN 0.86 defaults should satisfy it — confirm the number Play states in the console rather than trusting the default)
- [ ] AAB, not APK (already set — `buildType: "app-bundle"`)
- [ ] Release notes written
- [ ] Rollout percentage chosen (staged rollout is worth it for a first release)

---

## 6. Pre-submit smoke test, on the release build

Run against the **production AAB on a physical device**, signed in as a brand-new account —
not mock mode, not web, not a preview APK.

- [ ] Cold start with the backend asleep — the loading state is honest, not a crash
- [ ] Sign up → verify email → land on Home (email verification redirect was fixed in `1a0708d`; confirm it works from a real inbox)
- [ ] Share a link from Chrome/Instagram via the OS share sheet → toast appears → the app was **not** brought to the foreground → the save appears when you open the app
- [ ] Share while airplane-mode is on → reopen with network → the save still lands (this is the durable-queue path from 2026-08-12, never run on a device)
- [ ] Force-stop the app right after sharing → reopen → the save is recovered (`ShareRecovery`)
- [ ] A save completes the pipeline and renders a typed card
- [ ] Search returns results, and returns nothing sane for gibberish
- [ ] Create a Space, invite, join from a second account
- [ ] Delete account → signed out → cannot sign back in
- [ ] Rotate / dark mode / large font size don't break layout

---

## Order to do this in

1. **Start the closed test today** if the account is individual — it's a 14-day wall clock (§2.1).
2. **Fix eas.json's production env** (§1.1) — without it the release is dead on arrival.
3. **Add versionCode + submit config** (§1.2, §1.3).
4. **Build a production AAB and run it on a real device** (§1.6) — expect to find things, given how much of the app is unverified on hardware.
5. **Privacy policy + deletion URL, hosted and linked** (§1.4, §4.3).
6. **Decide the backend hosting tier** (§1.5).
7. **Store listing assets** (§3).
8. **Data safety + content declarations** (§4).
9. **Smoke test on the release build** (§6), then submit.
