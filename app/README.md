# Weavr — mobile app

Expo (React Native + TypeScript) with expo-router. Four screens from the Claude
Design mockups plus a full personalisation layer.

Identifiers, fixed alongside the backend package name:

| | |
|---|---|
| Expo slug | `weavr` |
| iOS bundle | `com.weavr.app` |
| Android package | `com.weavr.app` |
| App Group | `group.com.weavr.app` |
| Keychain access group | `com.weavr.shared` |

## Running it

Expo Go will never work here — `expo-share-extension` and
`react-native-purchases` are native modules, so an **EAS dev client** is
required. Neither is installed yet, so today the JS bundle also runs in a plain
dev build:

```bash
cd app
cp .env.example .env      # then fill it in — the app will not start without it
npm install
npx expo run:android      # or run:ios — prebuilds the native project
npm run typecheck
```

`npx expo export --platform android` bundles without a device and is the fastest
check that nothing is broken.

**Configuration.** Three `EXPO_PUBLIC_*` variables, all of them public by
definition — they are inlined into the JS bundle. The service-role key must never
appear here. Missing values are collected rather than thrown, so a fresh clone
shows a screen naming the variable instead of a white crash; the check is in
[src/api/config.ts](src/api/config.ts).

The one that catches people is `EXPO_PUBLIC_API_BASE_URL`: `localhost` is the
*device*, not your machine. Android emulators reach the host at `10.0.2.2`, and a
physical device needs the machine's LAN IP. There is deliberately no default,
because a wrong one fails as an opaque timeout. These are read at **build** time,
so restart the bundler after changing them.

## What's real vs. stubbed

| Area | State |
|---|---|
| Theme system (palettes, accents, AMOLED, fonts, covers) | **real** — 78 palette combinations, all audited for WCAG AA |
| Preferences (persisted to AsyncStorage) | **real** |
| Appearance settings | **real** — every control writes through and takes effect immediately |
| Auth (Supabase email/password) | **real, not yet run on a device** |
| Home feed ← `GET /v1/saves` | **real, not yet run on a device** — loading / error / empty / per-status states |
| Capture → `POST /v1/saves` | **real, not yet run on a device** — the Paste Link tile only |
| Library / Spaces / Continue / digest | **sample content** — these need features that do not exist yet |
| Other capture tiles | **inert** — visibly disabled until their capture surfaces exist |
| Share extension / silent capture | **absent** — the toggle exists, the native side does not |
| RevenueCat | **absent** |

The feed and Capture are wired to the real API. What is left on sample content is
[src/data/sampleContent.ts](src/data/sampleContent.ts): the Continue rail, the
weekly digest, Spaces and the Library groups all depend on pipeline output or
collaboration features that no endpoint serves yet.

### What "not yet run on a device" means

`tsc --noEmit` is clean and `expo export` bundles. That proves every module
resolves and the types line up with the Java DTOs. It proves **nothing** about
runtime: no screen has been rendered, no request has left a device, and sign-in
has never succeeded. There is no dev build yet. Until `npx expo run:android`
says otherwise, treat all three "real" API rows above as written-but-unproven.

The most likely first failures, in order: `EXPO_PUBLIC_API_BASE_URL` pointing
somewhere the device cannot reach, and a new account whose email has not been
confirmed. If requests fail even with a reachable URL, check whether the platform
is blocking **cleartext HTTP** to a LAN IP — Android blocks it by default outside
debug builds, and iOS ATS blocks it unless excepted. This has not been tested
either way here, because no native project has been generated yet.

### Deferred by design — easy to mistake for bugs

**Nothing polls, and a save never leaves `processing`.** The job runner exists now
and claims the job within seconds — but the handler behind it is a stub that
records an `accepted` stage and stops, because nothing yet fetches captions,
metadata or audio. So there is still no state transition for a poller to observe,
and polling would spin for nothing. Pull-to-refresh covers it until the
extraction cascade lands, or the push notification the pipeline is meant to send.
A freshly created save keeps its "Processing" pill; that is correct, not stuck.

**`POST /v1/saves` is not idempotent yet.** A retry creates a second save. The
server dedupes the *job* by save id, but not the save. Two callers will hit this:
the share extension's background upload, and the Paste Link tile if a user taps
twice on a slow network. Fix before silent capture ships.

**Auth is email/password, not anonymous.** Phase 1 of the plan called for
anonymous auth; password sign-in was used instead because it is the path already
proven end-to-end against this Supabase project, and anonymous sign-in needs a
dashboard toggle nobody has enabled. The cost is testing friction: a new account
must have its email confirmed through the admin API (service-role key) before it
can sign in. `signUp` surfaces this rather than failing silently.

**The session lives in AsyncStorage, which the share extension cannot read.**
Moving it to a shared Keychain access group — storing the **refresh** token, not
just the access token — is a prerequisite for silent capture and is the retrofit
the note below warns about.

## Layout

```
app/                    expo-router routes — thin wrappers and guards only
  _layout.tsx           providers, font loading, splash gate, config check
  index.tsx             the tab shell (Home / Library / Spaces + FAB)
  sign-in.tsx           redirects to / when a session exists
  capture.tsx           the Capture sheet, as a transparent modal
  appearance.tsx        Appearance settings
src/
  api/
    config.ts           EXPO_PUBLIC_* reading + MISSING_CONFIG
    types.ts            wire types mirroring the Java DTOs
    client.ts           fetch wrapper, auth header, ApiError mapping
  auth/                 Supabase client + SessionProvider
  saves/                the feed provider and its formatting helpers
  theme/
    palettes.ts         surface families × accents → a Palette
    contrast.ts         WCAG luminance maths; the on-colour pickers
    tokens.ts           spacing, radii, elevation, durations
    typography.ts       the type scale, per font choice
    covers.ts           the nine cover gradients
    ThemeProvider.tsx   preferences + OS scheme → Theme
  prefs/                AsyncStorage-backed preference store
  components/           Card, Chip, ListRow, BottomNav, Glyph, HatchThumb, …
  screens/              the five screens
  data/                 sample content
```

## The theme system

Two independent axes, following PennyWise's split of `ThemeStyle` from
`AccentColor`:

- **Surface family** — the neutral chrome. `weavr` is the warm-paper set from the
  mockups (their oklch values converted to sRGB); `rosepine` is Rosé Pine
  Dawn/Main.
- **Accent** — 13 hues, each with a light and a dark variant so an accent stays
  recognisable across a theme switch instead of glowing in dark mode. Twelve are
  Rosé Pine; `weavr` is the mockups' violet.

Times light/dark, times AMOLED, that is 78 combinations — which is only
affordable because **every `on*` colour is computed, not tabulated**
([contrast.ts](src/theme/contrast.ts)). Three helpers, in increasing order of
force:

- `onColorFor` — the better of two candidates. Ported from PennyWise.
- `bestOnColor` — the first candidate that clears AA, else pure black or white.
  Needed because "better of two" is not "readable": on Rosé Pine Dawn's Rose,
  white scores 2.1:1 and the palette ink 2.8:1, so the better one still fails.
- `ensureContrast` — keeps the hue and lightens/darkens until it clears AA. This
  is what `accentText` uses; replacing Slate-on-dark with white would clear the
  ratio but stop reading as an accent.

`palette.accent` is for fills, `palette.accentText` for the accent as text. They
are the same colour for most combinations and differ only where the raw accent
would fail — the neutral accents (Slate, Overlay, Muted) in dark mode.

All 78 combinations clear WCAG AA on body text, muted text, the FAB glyph, the
digest label, and both nav-pill states. Worst case is 4.50:1.

## Personalisation

Everything on the Appearance screen, all persisted:

| Control | Options |
|---|---|
| Theme | System / Light / Dark |
| AMOLED black | dark mode only; true-black surfaces |
| Palette | Weavr / Rosé Pine, with live swatch previews |
| Accent | 13 hues |
| Cover | None + 8 gradients, washed behind the Home header |
| Typeface | Sora / System, with a live specimen |
| Navigation | Floating pill (as designed) / Docked bar |
| Blur effects | translucent nav and sheet, or flat fills |
| Open app when saving | **off by default** — silent capture |
| Greeting name | shown on Home |

There is no Material You / wallpaper-colour option. PennyWise gets it from
`dynamicLightColorScheme(context)`; React Native has no equivalent without a
native module, so the 13 accents stand in for it.

## Things to know

**Sharing must not open the app.** The *Open app when saving* toggle already
exists and defaults to off, but the iOS share extension does not. When it lands,
the toggle has to be mirrored into the App Group at write time — the extension is
a separate process and cannot read AsyncStorage. The call site is already in
place: [src/prefs/shareExtensionBridge.ts](src/prefs/shareExtensionBridge.ts) is
a deliberate no-op so the mirroring cannot be forgotten.

Store the **refresh** token, not just the access token. Supabase access tokens
are short-lived and the extension will often run with an expired one — decide
this before writing the extension; retrofitting it is painful.

**Icons are geometry, not a font.** The mockups draw every icon from primitives
— rings, squares, rotated diamonds, a plus from two bars. [Glyph](src/components/Glyph.tsx)
reproduces them as views, which is faithful and drops a dependency plus its
font-loading race. Swapping in a real icon set later is a change to one file.

**The tab shell is state, not routes.** Home / Library / Spaces are kept mounted
once visited so switching preserves scroll position; they render lazily. Capture,
Appearance and sign-in are real routes.

**Enums are lower-case on the wire.** Every API enum implements `DbEnum`, whose
`db()` carries `@JsonValue` — so it is `"processing"`, not `"PROCESSING"`. Getting
this wrong fails at the `@JsonCreator` on the way in and reads as a 400 with no
obvious cause. [src/api/types.ts](src/api/types.ts) mirrors the Java one-for-one.

**A 401 has a completely empty body** — zero bytes, no content-type; the reason
lives in the `WWW-Authenticate` header, not JSON (verified against the running
service). That is why the client's response parsing is wrapped in a `try/catch`
with a kind-aware fallback message: without it, every expired token would surface
as a JSON parse error rather than an auth problem.

**The access token is read per request, never cached.** `supabase.auth.getSession()`
refreshes an expired token on the way out, which matters because a save can be
posted minutes after the app was last foregrounded. The refresh timer is stopped
while the app is backgrounded — on native the JS runtime is suspended, so a timer
that fires there wakes to a stale clock.

See [docs/implementation-plan.md](../docs/implementation-plan.md), Phase 1–2.
