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
| Auth (Supabase email/password) | **real, works on a device** — sign-in succeeded on Android on 2026-08-01 |
| Home feed ← `GET /v1/saves` | **real, works on a device** — loading / error / empty / per-status states; seen rendering live saves on Android |
| Bottom navigation | **real, was broken on the first device run** — painted nothing on Android; fixed by hoisting it out of the shell wrapper, and that fix is **unverified** |
| Capture → `POST /v1/saves` | **real, not yet run on a device** — the Paste Link tile only, and the nav bug blocked reaching it |
| Library / Spaces / Continue / digest | **sample content** — these need features that do not exist yet |
| Other capture tiles | **inert** — visibly disabled until their capture surfaces exist |
| Silent capture — Android | **built, never run on a device** — `ShareReceiverActivity` + `ShareUploadWorker` via a config plugin; no Android SDK on this machine to build or run it |
| Silent capture — iOS | **absent** — the toggle exists, the native share extension does not |
| RevenueCat | **absent** |

The feed and Capture are wired to the real API. What is left on sample content is
[src/data/sampleContent.ts](src/data/sampleContent.ts): the Continue rail, the
weekly digest, Spaces and the Library groups all depend on pipeline output or
collaboration features that no endpoint serves yet.

### What "not yet run on a device" means

`tsc --noEmit` is clean and `expo export` bundles. That proves every module
resolves and the types line up with the Java DTOs. It proves **nothing** about
runtime, and on 2026-08-01 a device demonstrated exactly that: the app opened,
signed in and rendered a live feed, and the entire bottom navigation — the
floating pill *and* the capture FAB — failed to paint, which three green checks
in a row (typecheck, bundle, browser dev server) had all been blind to.

So the levels are now: Home's feed and sign-in are **seen working**; the nav fix
is **written and unverified** (no emulator here, so it was reasoned by
elimination, not reproduced); everything else in the table is still
written-but-unwatched. If the nav is still missing, flip Appearance →
Navigation → Docked: `floating` and `normal` share the absolute-overlay root and
share nothing else, so which of them fails localises the fault in one tap.

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
  settings.tsx          Settings, presented as a morph out of the Home gear
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
  motion/
    haptics.ts          the `haptics` preference gate around expo-haptics
    morph.ts            shared state for the Home ↔ Settings container transform
    MorphPresentation.tsx  the morph itself: surface, backdrop, collapse
                        (the springs and press depths live in theme/motion.ts)
  prefs/                AsyncStorage-backed preference store
  share/
    nativeShareConfig.ts mirrors session + "open app when saving" to a file
                         Android's share Activity/Worker read (no-op on iOS)
  components/           Card, Chip, ListRow, BottomNav, Glyph, HatchThumb, …
  screens/              the five screens
  data/                 sample content
plugins/
  withAndroidShareReceiver.js   config plugin: adds ShareReceiverActivity +
                                ShareUploadWorker to the generated android/
  android-templates/share/      the Kotlin sources the plugin copies in
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
exists and defaults to off. Android's half is real:
[src/share/nativeShareConfig.ts](src/share/nativeShareConfig.ts) mirrors the
session and the toggle into a plain JSON file in the app's private files dir,
which `ShareReceiverActivity`/`ShareUploadWorker` (added by
[plugins/withAndroidShareReceiver.js](../plugins/withAndroidShareReceiver.js))
read directly — no App-Group-style bridging needed, since on Android the share
Activity and the JS runtime are the same process. iOS's share extension does
not exist yet; when it lands, the toggle additionally has to be mirrored into
the App Group at write time, because that extension *is* a separate process
and cannot read AsyncStorage. The call site is already in place:
[src/prefs/shareExtensionBridge.ts](src/prefs/shareExtensionBridge.ts) is a
deliberate no-op so the iOS mirroring cannot be forgotten.

Store the **refresh** token, not just the access token, for iOS. Supabase
access tokens are short-lived and the extension will often run with an expired
one — decide this before writing the extension; retrofitting it is painful.
(Android's worker hits the same problem in a smaller way: `nativeShareConfig.ts`
mirrors the access token, which can expire before a queued upload retries. Not
yet handled — see Known gaps in the root README.)

**Icons are geometry, not a font.** The mockups draw every icon from primitives
— rings, squares, rotated diamonds, a plus from two bars. [Glyph](src/components/Glyph.tsx)
reproduces them as views, which is faithful and drops a dependency plus its
font-loading race. Swapping in a real icon set later is a change to one file.

**The tab shell is state, not routes.** Home / Library / Spaces are kept mounted
once visited so switching preserves scroll position; they render lazily. Capture,
Appearance and sign-in are real routes.

**Settings is a container transform, not a push.** The surface grows out of the
gear on Home and collapses back into it —
[src/motion/MorphPresentation.tsx](src/motion/MorphPresentation.tsx), with the
shared state in [src/motion/morph.ts](src/motion/morph.ts). Four things about it
are load-bearing rather than stylistic:

- **The stack does no transition of its own.** The route is a `transparentModal`
  with `animation: 'none'` and `gestureEnabled: false`. A stack animation cannot
  be played backwards on demand, so a push/pop pair *always* cuts at the moment
  of navigation however well the two halves are matched; owning both directions
  is the only way the collapse can mirror the expansion. The swipe-back gesture
  is off for the same reason — it would pop the route and skip the collapse.
- **The origin is measured at press time, and navigation waits for it.**
  `measureInWindow` is async and the presented screen reads the rectangle in its
  first animated style, so navigating first means the very first open expands
  from a stale rectangle. The Home header also scrolls, so a value cached at
  layout is wrong the moment the user scrolls. The anchor needs
  `collapsable={false}`: Android flattens views that draw nothing out of the
  native hierarchy, and a flattened view has no position to measure.
- **One shared value drives both screens.** The surface's geometry, radius and
  colour, the backdrop blur, the content fade *and* the shell receding behind it
  are one `morphProgress` sampled in different places, so they cannot drift. It
  lives at module scope because its two consumers are on opposite sides of a
  navigation boundary — the route below stays mounted but is not a parent, so no
  component could own it for both. It is read inside worklets, never as a prop.
- **Only the surface animates layout.** `left`/`top`/`width`/`height` change on
  one childless frame; the screen inside is a fixed-size subtree moved with
  `transform` and faded with `opacity`, counter-translated so it holds still
  while the aperture opens over it. That is what keeps the cost of the morph
  independent of how complex the screen is — and what makes it read as a window
  opening rather than a page flying in.

The spring (`Spring.morph`) is the loosest in [motion.ts](src/theme/motion.ts) at
ζ ≈ 0.71, so it overshoots a few percent and settles. Position and size are
interpolated *unclamped* so that overshoot is felt; radius and colour are clamped,
because a negative corner radius is not a shape. Reduced Motion skips the whole
thing — including the shell recede, since a snap is the jolt the setting exists
to avoid.

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
