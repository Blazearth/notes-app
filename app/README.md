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
npm install
npx expo run:android      # or run:ios — prebuilds the native project
npm run typecheck
```

`npx expo export --platform android` bundles without a device and is the fastest
check that nothing is broken.

## What's real vs. stubbed

| Area | State |
|---|---|
| Theme system (palettes, accents, AMOLED, fonts, covers) | **real** — 78 palette combinations, all audited for WCAG AA |
| Preferences (persisted to AsyncStorage) | **real** |
| Home / Library / Spaces / Capture sheet | **real UI, sample content** — see below |
| Appearance settings | **real** — every control writes through and takes effect immediately |
| API calls | **absent** — nothing talks to `/v1/saves` yet |
| Auth (Supabase sign-in) | **absent** |
| Share extension / silent capture | **absent** — the toggle exists, the native side does not |
| RevenueCat | **absent** |

Screen content comes from [src/data/sampleContent.ts](src/data/sampleContent.ts),
transcribed from the mockups. The backend can already serve `GET /v1/saves`, but
no save has ever been created with a real Supabase JWT, so wiring the screens to
it would be building on an unverified path. The sample items keep the shape of
`SaveResponse` (a `knowledgeType` key, a source line) so the swap is mechanical.

## Layout

```
app/                    expo-router routes — thin wrappers only
  _layout.tsx           providers, font loading, splash gate, stack
  index.tsx             the tab shell (Home / Library / Spaces + FAB)
  capture.tsx           the Capture sheet, as a transparent modal
  appearance.tsx        Appearance settings
src/
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
once visited so switching preserves scroll position; they render lazily. Capture
and Appearance are real routes.

See [docs/implementation-plan.md](../docs/implementation-plan.md), Phase 1–2.
