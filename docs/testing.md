# Testing guide

How to check work in this repo, what each check actually proves, and which
mistakes it cannot catch.

The distinction that matters here: **compiling is not running, and running is not
running against the real thing.** Being precise about which level a claim comes
from is the difference between a useful status and a misleading one.

That is not an abstract worry. The extraction cascade had 27 passing tests and
three real defects, and the entire gap between those two numbers was that the
external binary had been mocked — see [what running the real binary
found](#what-running-the-real-binary-found). The app proved the same point one
level lower on 2026-08-01: it typechecked, it bundled, it mounted clean in a
browser dev server — and the first time it opened on a real Android phone the
entire bottom navigation failed to paint, so there was no way to leave the Home
screen. Three green checks in a row, none of which could see it.

## Looking at a screen without a device

Two rendering bugs in one session were diagnosed with this, after reasoning
about the code got the first one **wrong**. It costs about a minute:

```bash
cd app && BROWSER=none npx expo start --web --port 8082   # 8081 is usually taken
CHROME="/c/Program Files/Google/Chrome/Application/chrome.exe"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --window-size=412,915 --virtual-time-budget=45000 \
  --screenshot=shot.png "http://localhost:8082/capture"
```

Then `Read` the PNG. Four things that are not obvious:

- **Add `--force-prefers-reduced-motion` to see the settled layout.** The
  virtual clock does not settle Reanimated *springs*, so a plain screenshot
  catches every animated screen mid-flight and a mid-flight screen looks
  broken. That flag makes `useReducedMotion()` true, which skips springs and
  entrance animations, so the final layout renders immediately. Comparing the
  two is what separates "the layout is wrong" from "the entrance never
  finished" — a distinction that decides the fix.
- **Most routes redirect to sign-in.** A throwaway route under `app/app/` that
  mounts the component with fake props reaches it without a session; delete it
  afterwards.
- **Use a spare port.** 8081 is normally serving a real device, and killing it
  interrupts whoever is testing on a phone.
- **It cannot see anything platform-native, and on layout it can actively
  mislead.** Chrome renders through `react-native-web`, so sizing follows CSS,
  not Yoga — and the two disagree in at least one way that has already cost
  time here: `flex: 1` (i.e. `flexBasis: 0`) on a child of an auto-height
  column collapses to **zero height** under Yoga, while CSS sizes the container
  to max-content and the child measures normally. The Capture sheet was broken
  on the phone and pristine in Chrome for exactly that reason. Treat "correct
  on web, wrong on device" as a Yoga sizing rule until proven otherwise, and
  never read a green screenshot as a device.

### Driving the app, not just screenshotting a URL

Screenshotting a route cannot check anything that depends on **navigation
history**. The capture sheet is a `transparentModal`, so opening `/capture`
directly gives it no Home to sit over — and "is the screen behind it still
there?" is exactly the question that needs answering. Loading the URL shows a
sheet on a void whether the bug is present or not.

Chrome's DevTools Protocol closes that gap, with no dependencies: Node 24 ships
a global `WebSocket`, which is all CDP needs. Start Chrome with
`--remote-debugging-port=9222 --user-data-dir=<scratch>`, then `GET
http://127.0.0.1:9222/json` for the page target, open its
`webSocketDebuggerUrl`, and send:

- `Page.navigate` to land on Home,
- `Runtime.evaluate` to find a control — query by `[aria-label="Capture"]`, the
  same string a screen reader uses, so the probe breaks loudly if the label is
  ever dropped — and return its `getBoundingClientRect()`,
- `Input.dispatchMouseEvent` (`mousePressed` then `mouseReleased`) at its
  centre,
- `Page.captureScreenshot`.

That sequence caught the FAB backdrop bug and then proved both halves of the
fix: Home blurred beneath the open sheet, and sharp again after a tap on the
backdrop. Sleep between steps rather than racing the springs — the virtual
clock is not in play here, so these are real milliseconds.

**Scroll into view before measuring, and assert that you did.** A control below
the fold has a `getBoundingClientRect().y` past the viewport, and a mouse event
dispatched at that coordinate lands on nothing — *silently*. The failure reads as
"the tap did not work", which sends you debugging the handler instead of the
probe. This cost ten minutes on the local-first work: the Progress strip sits low
on a save's detail screen, and the write looked broken until the click helper grew
an `el.scrollIntoView({ block: 'center' })` plus an `inView` check on the measured
rectangle. Return `false` from the helper when the rectangle is not in view, so a
miss is a failed assertion rather than a mystery.

**`innerText` reflects rendered text, so `textTransform` breaks string matches.**
`SectionLabel` uses `variant="sectionLabel"`, which is `textTransform:
'uppercase'` — so a check for `text.includes('Unsent changes')` fails against a DOM
that says `UNSENT CHANGES`. Match case-insensitively (`/unsent changes/i.test`) or
the assertion tests the stylesheet. Worth knowing in both directions: an assertion
that something is *absent* passes for the wrong reason here.

**`document.body.innerText` is the whole shell, not the screen under test.**
`app/index.tsx` keeps Home, Library and Spaces mounted as panes — so a check run
on the Spaces tab also sees Library's "12 saves" and its collection cards'
"3 titles · 2 sources". The first pass of the knowledge-spaces S0 probe both
passed and failed against strings belonging to a different pane, which is worse
than failing: it reads as a bug in the feature. Scope every read — navigate
straight to a screen that owns its own route (`/space/{id}` gives a clean
document), and query list items by their `aria-label` rather than by body text.

**A probe against mock mode has to be one page load.** `mockRepository` is
module-scope state that resets on reload — its own doc says so, and that is the
right lifetime for it — so a `Page.navigate` between a write and the read that
checks it discards exactly the thing under test. Every earlier probe here could
reload freely because what it checked was the local *store*, which persists to
`localStorage` on purpose. The S3/S4 run could not: entity comments, pins and the
shared shopping list all live in the repository. So it moves the way a user does
(tap a card, tap a tab, tap back), and where a screen's own `load()` has to run
again it **backs out to the Spaces list and re-enters**, which remounts it. That
is not a workaround so much as a stronger test: it exercises the affordances *and*
proves the write reached the repository rather than only rendering where it was
typed.

**The mounted-pane trap has a second form, and it fails misleadingly.** The
paragraph above is about *reading* the wrong pane; this is about *clicking* it.
The shell keeps Home, Library and Spaces mounted **and** a pushed route stacks on
top, so several elements can carry the same `aria-label` while all but one are
invisible. A helper that takes the first match clicks a hidden pane, and because
the element was found the failure reads as "the tap did not work" — which sends
you debugging the handler. Filter to elements with a non-empty
`getClientRects()` and take the **last**: the topmost screen renders later in
document order.

**React Native Web does not emit `aria-selected`.** `accessibilityState={{
selected }}` on a `Pressable` — what `Segmented` uses for its tabs — produces
`role="tab"` with no `aria-selected` attribute at all, so every tab reads
`null` and a "which tab is open" assertion silently checks nothing. **The same
holds for `aria-checked`** (confirmed on the shopping list's
`accessibilityRole="checkbox"` rows during the S4 run), so treat it as a
property of `accessibilityState` generally rather than a quirk of one attribute:
assert from rendered content, never from the attribute. Assert on
the rendered *content* of the open tab instead; it is what the user sees, and
it also catches a tab that is selected but renders the wrong body.
`Segmented` now also sets an explicit `accessibilityLabel` per tab — without
one, a tab whose only text is a child node has no stable handle to *click*
either, so a probe cannot switch tabs at all. That was a real accessibility
gap, not a test affordance: a screen reader had the same problem.

**A route file existing is not a route working.** `app/session/[nodeId].tsx`
was created, typechecked, bundled, and rendered perfectly when its URL was
visited directly — and the button that pushed to it navigated nowhere, because
no matching `<Stack.Screen>` had been added to `app/_layout.tsx`. Nothing but a
*click* catches this: a direct `Page.navigate` resolves the file-system route
and looks fine, so screenshotting the destination proves the screen and not the
way in. Drive the affordance the user actually taps, then assert `location.href`
changed — a text assertion alone reads as "the destination is wrong" rather than
"you never left".

**A dismissed `Modal` still eats the next click.** React Native Web portals a
`Modal` *above* the document, and a `Input.dispatchMouseEvent` fired straight
after clicking its dismiss control lands on the backdrop that has not finished
unmounting. The click silently does nothing, which is indistinguishable from a
broken handler on the element underneath — and the click helper reports success,
because the element was found and in view. This cost a debugging pass on the K7
run: the same click worked in isolation on a clean document. **Re-navigate after
closing a modal** rather than sleeping longer and hoping; `goto()` gives a
document with no portal in it, which is a guarantee rather than a race.

**Build the selector by comparing the attribute, not by interpolating into
one.** `document.querySelector('[aria-label="…"]')` breaks on the first label
containing a quote or an apostrophe ("Farmer's carry"), and escaping schemes get
one case wrong. `[...document.querySelectorAll('[aria-label]')].find(e =>
e.getAttribute('aria-label') === L)` with `L` injected as `JSON.stringify(label)`
has no escaping problem at all. The failure is a `SyntaxError` from
`Runtime.evaluate`, which reads as a broken probe rather than a bad label.

**Three things a CDP run can check that a screenshot cannot, and they are where
the bugs are:** `localStorage` (the local-first store's snapshot is a plain JSON
blob — every claim about what was persisted, and what was deliberately *not*,
is one `evaluate` away), the DOM's accessibility labels (query by the same string
a screen reader uses, so the probe breaks loudly if a label is dropped), and state
*after* a reload. On a freshly launched Chrome the page target starts on
`about:blank`, where reading `localStorage` is a `SecurityError` rather than an
empty store — poll `location.origin` until it is the app's before touching it, and
check `location.href` is not `chrome-error://chromewebdata/` when a navigation
mysteriously produces nothing (that one means Metro is not running).

**Assert inside the window that belongs to the code under test.** L5's search
screen answers locally first and merges the server's reply in ~350ms later, and
the first attempt to check "a schema key matches nothing" waited for the merged
list — which passed the *mock* server's crude
`JSON.stringify(structuredData).includes(q)` straight through and failed the
assertion against code that was correct. Sampling at 180ms, before the debounce
elapses, made the check about the local index again. Whenever two producers write
to one surface, the timing of the read is part of the assertion.

**The probe's own writes survive it, so a second run starts somewhere else.**
`memoryStore` persists to `localStorage` on purpose (it is what makes "paints
from cache on a second load" testable at all), and mock mode seeds it once. So a
probe that clicks a status pill leaves that status behind: an assertion pinned to
the exact label `"Call of the Night: Want to watch. Tap to change."` passes on a
clean `--user-data-dir` and fails on every run after it, which reads as a
regression in the feature. Find such a control **by prefix** and click whatever
label it currently carries, then assert that the rendered text *changed* rather
than that it reached a particular value. Reserve exact-label matching for
controls the probe does not itself mutate. (Deleting the Chrome profile between
runs also works and is worse — it throws away the only state that makes a
second-load assertion mean anything.)

**To exercise a failure path, patch the fixture and diff it back.** There is no
way to make `mockRepository` reject from inside the page, so the technique is:
back the file up, edit the one method to throw the `ApiError` kind you want, let
Metro reload, run the probe with a flag that enables the extra checks, then
restore and `diff -q` against the backup. That is how "local results survive a
failed server search" was checked, and how L3's retry and terminal paths were.
The `diff -q` is not optional — a fixture left patched is a silent, realistic lie
in every later run.

The Yoga caveat above still applies. This drives a **browser**; it sees more of
the app than a static screenshot, and still nothing platform-native.

React Native also does not clip overflow by default, so a view with a collapsed
box still paints its children at full size. A zero-height container therefore
looks like a stuck animation rather than a layout fault — which is precisely
the wrong direction to start debugging in. Check the *spacing of what comes
after* the suspect element: if the next sibling has ridden up, the box is
collapsed and no animation is involved.

---

## What each layer proves

| Check | Proves | Does **not** prove |
|---|---|---|
| `./mvnw test` | Logic, error classification, backoff maths, process handling | That any SQL is valid, or that Spring can wire the context |
| `./mvnw clean verify` | The above, plus the jar builds | Anything about the database |
| Booting the API | Context wiring, every bean, Flyway, both pooler URLs | That endpoints behave correctly |
| `curl` against a running API | Real request/response shapes and status codes | Anything on a device |
| Headless Chrome against `expo start --web` | That a screen **lays out and paints** — the first check here that can see a rendering bug at all | Anything platform-native: Android view clipping, draw order, native gesture handling |
| `WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest` | **The real binary.** That yt-dlp accepts our argument lists, that the JSON field names exist, and that subtitle files land where we look | Any platform except the one you passed |
| `WEAVR_LIVE_OCR=1 ./mvnw test -Dtest=OcrLiveTest` | **The real ffmpeg and tesseract.** That the filter graph selects frames at all, that tesseract accepts our flags and TSV columns, and that a clean card clears the gate while a textless clip does not | Anything about real-world visual noise — that is the eval set's job |
| `tsc --noEmit` (app) | Types line up, including against the Java DTOs | That a single screen renders |
| `expo export` (app) | Every module resolves; the bundle builds | Same — nothing has run |
| `expo run:android` | **Actual runtime.** Nothing on the app side has reached this row | — |

The backend now reaches its top row; the app does not reach its own. Treat every
app-side claim accordingly.

### External binaries

The cascade shells out to yt-dlp, ffmpeg and — since the visual tier landed —
tesseract. None is a Java dependency, so Maven will not tell you they are
missing; the symptom is every save retrying until it exhausts `max_attempts`.

```bash
yt-dlp --version && ffmpeg -version | head -1 && tesseract --version | head -1
```

On the dev machine yt-dlp and ffmpeg live in `C:\Users\Saksham\tools\bin`, on
the user PATH: yt-dlp is the standalone `.exe` from GitHub releases, ffmpeg the
gyan.dev "essentials" build. That build is statically linked, hence ~94 MB per
binary — **do not copy that approach into the Docker image**; use the distro
package. Override the binary location with `WEAVR_YTDLP_BINARY` /
`WEAVR_FFMPEG_BINARY` if it is not on PATH.

**tesseract 5.5.0** came from `winget install tesseract-ocr.tesseract` (the
UB-Mannheim build) and installs to `C:\Program Files\Tesseract-OCR`, which the
installer does **not** add to PATH. Either add it or set
`WEAVR_TESSERACT_BINARY` to the full path; `OcrLiveTest` reads the same
variable. Confirm the language data is actually there — an install with no
`eng` reads every frame as nothing, silently:

```bash
tesseract --list-langs      # must include eng
```

In the container, use `tesseract-ocr` plus `tesseract-ocr-eng` from the distro
and keep `tessdata_fast`: OCR only has to be good enough for the model to
repair, and the fast English model is ~2 MB against ~15 MB for the accurate one.

---

## Running the checks

### Backend

```bash
cd api
./mvnw test           # 595 tests, NO database or .env needed (8 skip: see below)
./mvnw clean verify   # the above plus packaging
```

Eight tests skip by default, in five opt-in groups. All are gated rather than
deleted because a red build caused by a third party rate-limiting us is worse
than no signal — but an ungated *absence* of the check is worse than either.

`YtDlpLiveTest` (2) talks to YouTube:

```bash
WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest
WEAVR_LIVE_YTDLP=1 WEAVR_LIVE_URL=https://... ./mvnw test -Dtest=YtDlpLiveTest
```

`OcrLiveTest` (3) needs ffmpeg and tesseract but **no network** — it renders its
own fixture videos with ffmpeg, so it is safe to run in a container build and is
the cheapest way to prove the toolchain is actually installed:

```bash
WEAVR_LIVE_OCR=1 ./mvnw test -Dtest=OcrLiveTest
```

`KnowledgeTypeRegistrySchemaLiveTest` (1) posts the real production request —
the whole `anyOf` schema and system prompt — to the real Gemini API, and so
**costs one of the 500 daily requests**. Run it after adding a knowledge type or
a field, which is exactly when a schema too large or too deeply nested would
first fail:

```bash
WEAVR_LIVE_GEMINI=1 ./mvnw test -Dtest=KnowledgeTypeRegistrySchemaLiveTest
```

`ExtractionCanaryLiveTest` (1) runs the canary for real — every default target
through every configured provider — and prints a table. `ExtractionCascadeLiveTest`
(1) runs the real production cascade on one URL through the free tiers (RapidAPI,
Data API, yt-dlp probe + captions); ASR/OCR/link/PDF are **not** wired and throw if
reached, so a pass means captions or metadata alone carried the save. Keys are read
from the environment; a missing one leaves that provider off rather than mocked:

```bash
WEAVR_LIVE_CANARY=1 ./mvnw test -Dtest=ExtractionCanaryLiveTest
WEAVR_LIVE_CASCADE=1 WEAVR_LIVE_URL=https://... ./mvnw test -Dtest=ExtractionCascadeLiveTest
```

**Both measure the machine they run on.** From a dev box that's a residential IP —
not Render's datacenter IP — and from this team's machine in India, TikTok is
unreachable at the network level (national ban), so a TikTok `TIMEOUT` from here
says nothing about TikTok. First run, 2026-10-01, residential IP: YouTube via
RapidAPI ✅ (captions, 5,142 chars, 5.7s), YouTube via yt-dlp ✅ (3.5s), Instagram
via yt-dlp ✅ (metadata, 436 chars, 4.8s), TikTok ❌ `TIMEOUT` (network block, 21.8s).

Run the yt-dlp pair after touching anything under `pipeline/ytdlp/` and expect
an occasional 429 that is not your fault; run the OCR trio after touching
anything under `pipeline/ocr/`, and especially after changing the ffmpeg filter
graph or the tesseract flags, neither of which any mocked test can judge.

The default suite needs no configuration at all — there is no `@SpringBootTest`,
so no context loads and nothing reads `.env`. The only environment variables any
test consults are `WEAVR_LIVE_YTDLP`, `WEAVR_LIVE_OCR` and `WEAVR_LIVE_GEMINI`,
whose sole effect is to enable the live groups above. If a test ever starts needing database or
Supabase credentials, that is a signal it has become an integration test and
should be named — and gated — like one.

To exercise the database, boot the service:

```bash
cd /path/to/repo
cp .env.example .env      # two DB passwords + SUPABASE_ANON_KEY
set -a && source .env && set +a
cd api && ./mvnw spring-boot:run
```

Then the smoke test in the [README](../README.md#smoke-test): mint a token
against Supabase Auth, `POST /v1/saves`, and watch the job runner claim it within
a couple of seconds.

### App

```bash
cd app
cp .env.example .env      # three EXPO_PUBLIC_* values
npm install
npm run typecheck                     # tsc --noEmit
npx expo export --platform android    # bundles without a device
npx expo run:android                  # the real test — needs a device or emulator
```

`npm run lint` is currently a trap: there is no ESLint config, so `expo lint`
drops into interactive setup. Either configure it or drop the script.

**A brand-new route fails `typecheck` until the dev server has served a page.**
`experiments.typedRoutes` is on, so `router.push('/paywall')` is checked against
a generated union in `.expo/types/router.d.ts` — and a route file that exists on
disk is not in that union yet. The failure is misleading: tsc says the literal
is "not assignable", which reads as a typo in a path that is plainly correct.
`npx expo export` does **not** regenerate it; only the dev server does, and only
once something actually requests a bundle. So:

```bash
npx expo start --web --port 8099 &   # then hit it once
curl -s -o /dev/null http://localhost:8099/
npm run typecheck                    # now the new route resolves
```

Adding the route to the `<Stack>` in `app/app/_layout.tsx` is a separate,
equally required step — see that file's comments for the two shipped routes that
rendered on a direct visit and navigated nowhere from inside the app.

---

## Techniques worth reusing

Four things in this repo could not be tested the obvious way. The workarounds
generalise.

### Running app logic with no test runner — compile the one module and execute it

`app/` has no Vitest or Jest, so its logic is normally only typechecked. But a
module that imports **types only** erases to plain JavaScript, which means it can
be compiled alone and run under node without the RN runtime, the path aliases or
a bundler:

```bash
cd app
npx tsc src/saves/detailModel.ts --outDir /tmp/dm \
    --module esnext --target es2022 --moduleResolution bundler --skipLibCheck
node /tmp/dm/run.mjs        # a script that imports ./detailModel.js and asserts
```

`tsc` complains it cannot resolve `@/api/types` and **emits the JavaScript
anyway** — the import was type-only, so there is nothing to resolve at runtime.
That error is expected, not a failure.

Drive it with the shapes the server actually produces —
`KnowledgeTypeRegistry`'s few-shot examples are the real contract, and using
them rather than invented fixtures is what makes this worth doing. It found a
bug on the first run (an unknown knowledge type rendered its title twice).

The same trick applies to `cardModel.ts` and `format.ts`. It is not a substitute
for a test runner, because nothing re-runs it — but it is the difference between
"the types line up" and "the function returns what I claimed".

### Testing process handling — spawn a real JVM, never a mock

`ExternalProcess` exists to prevent hangs: a full pipe buffer, a child that never
exits. A mocked `Process` passes whether or not the code is correct, because the
thing under test *is* the OS interaction.

So [`ProcessTestHelper`](../api/src/test/java/com/weavr/api/pipeline/ProcessTestHelper.java)
is a real `main` that the test spawns as a child JVM, reached via
`System.getProperty("java.home")` and the current classpath. It runs identically
on Windows and Linux, needs no shell, and reproduces the actual hazards:

- `flood` writes 1 MB to stdout **and** stderr. If the parent drained them in
  sequence the child would block at ~64 KB and the test would time out — which is
  exactly how the assertion is written (`assertThat(result.timedOut()).isFalse()`).
- `hang` never exits, proving `waitFor(timeout)` + `destroyForcibly()`.

**Rule of thumb:** if the bug you are guarding against is a *hang*, you have to
reproduce the hang. Assert on elapsed time or a timeout flag, not on a return
value.

### Auditing a combinatorial space — compile the module and run it

The app's palette has 78 combinations (2 surface families × 13 accents ×
light/dark/AMOLED). Eyeballing three of them proves nothing, and this is how a
genuine AA-contrast failure was found on the FAB glyph.

There is no test runner in the app yet, so the check compiles the two relevant
modules to CommonJS and drives them from Node:

```bash
cd app
SCRATCH=$(mktemp -d)
npx tsc src/theme/palettes.ts src/theme/contrast.ts \
  --outDir "$SCRATCH" --module commonjs --target es2020 --skipLibCheck --esModuleInterop

cat > "$SCRATCH/audit.js" <<'EOF'
const { buildPalette, ACCENT_IDS, SURFACE_FAMILY_IDS } = require('./palettes.js');
const { contrastRatio } = require('./contrast.js');

const pairs = {
  'body text on surface':  p => [p.surface, p.text],
  'muted text on surface': p => [p.surface, p.textMuted],
  'FAB glyph on accent':   p => [p.accent, p.onAccent],
  'label on container':    p => [p.accentContainer, p.onAccentContainer],
  'accent as text':        p => [p.background, p.accentText],
  'nav inactive label':    p => [p.navBg, p.navInactiveText],
};

const worst = {};
for (const family of SURFACE_FAMILY_IDS)
  for (const accent of ACCENT_IDS)
    for (const isDark of [false, true])
      for (const amoled of isDark ? [false, true] : [false]) {
        const p = buildPalette({ family, accent, isDark, amoled });
        const id = `${family}/${accent}/${isDark ? 'dark' : 'light'}${amoled ? '+amoled' : ''}`;
        for (const [name, pick] of Object.entries(pairs)) {
          const [bg, fg] = pick(p);
          const r = contrastRatio(bg, fg);
          if (!worst[name] || r < worst[name].r) worst[name] = { r, id, bg, fg };
        }
      }

let failed = false;
for (const [name, o] of Object.entries(worst)) {
  const ok = o.r >= 4.5;
  if (!ok) failed = true;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name.padEnd(24)} ${o.r.toFixed(2)}:1  ${o.fg} on ${o.bg}  (${o.id})`);
}
process.exit(failed ? 1 : 0);
EOF

node "$SCRATCH/audit.js"
```

Every pairing should clear **4.5:1** (WCAG AA for normal text). The current worst
case is 4.50:1. **Re-run this after touching `palettes.ts` or `contrast.ts`** —
adding an accent adds six new pairings, and there is no automation to catch a
regression yet.

### Verifying a migration — run it against the real schema and roll it back

A migration's SQL is the least-tested code in this repo: `./mvnw test` needs no
database, so nothing executes it until Flyway does, on a real deployment, once. A
statement that fails halfway leaves Flyway needing manual repair — and this project
has been caught twice by SQL that reads perfectly (`to_tsvector` being STABLE
rather than IMMUTABLE, and V11's `$.*` indexing JSON *keys* as well as values).

Postgres runs DDL transactionally, so the whole file can be executed against the
live schema and then discarded:

```java
try (Connection c = DriverManager.getConnection(System.getenv("WEAVR_FLYWAY_URL"), props)) {
    c.setAutoCommit(false);
    try (Statement s = c.createStatement()) {
        s.execute(Files.readString(Path.of("…/V15__sync.sql")));
        // …then assert against information_schema / pg_indexes / pg_policies,
        // and run the queries the new service will actually issue.
    } finally {
        c.rollback();   // the database is exactly as it was
    }
}
```

Run it with the **session pooler** URL (`WEAVR_FLYWAY_URL`), not the transaction
pooler — same reason Flyway itself needs it. Then `java -cp <pg-driver>.jar
Probe.java` (single-file source, no build).

Four things worth asserting beyond "it parsed":

- **That the objects exist**, via `information_schema.tables/columns`,
  `pg_indexes`, `pg_trigger`, `pg_policies` — a script can parse and still create
  nothing you expected.
- **That the queries the new code issues are accepted against the new shape.** A
  delta whose SQL only compiles in Java is not a delta.
- **`explain` on the query the new index exists for.** V15's added
  `saves_user_updated_idx` because V1's index is on `created_at`; the plan says
  `Index Scan using saves_user_updated_idx`, which is the difference between an
  index and a comment claiming there is one.
- **That the constraint the feature *is* actually constrains.** V16 exists so a
  repeated `Idempotency-Key` cannot create a second Space, so the probe runs the
  claim insert twice and asserts the second affects **zero** rows. Asserting the
  table exists would have passed against a migration that forgot the primary key
  — the mechanism has to be exercised, not inventoried. Same trip, `rollback()`
  discards both inserts.

A fifth, learned from V18: **a migration that *replaces* an index cannot be
checked by name.** V18 drops V5's `shopping_lists_one_open_per_user` and creates
a narrowed one with the identical name, so "does an index by that name exist"
passed against both the old shape and the new one — a check that could never
fail. Assert on `pg_indexes.indexdef` instead (`ilike`, because Postgres
normalises the predicate to upper case). The general form: when a migration
changes a thing rather than adding one, the assertion has to name what changed.

Two more practicalities that cost time on that run. **Pass the credentials
explicitly** — `WEAVR_FLYWAY_URL` carries no user or password (Spring supplies
them separately), and Supavisor answers a credential-less connection with a
confusing `no tenant identifier provided`. And **wrap a deliberate constraint
violation in a savepoint**, or the first "assert this is rejected" check aborts
the transaction and every check after it fails for an unrelated reason.

**This does not work for `create index concurrently`** — that cannot run in a
transaction, which is a reason to prefer the plain form in a migration unless the
table is large enough to care about the lock.

The same file+rollback shape suits any "does Postgres actually do X?" question.
Some need a write to answer at all: *does an `on delete set null` FK cascade fire
the row-level `BEFORE UPDATE` trigger?* cannot be read out of the catalog, because
the trigger exists either way. That one needs a throwaway user, space and save, a
delete, and a `finally` block that removes them — **inserted directly into
`auth.users`, never via `/auth/v1/signup`**, which emails a fake address and risks
the project's sending reputation on a bounce.

### Verifying response shapes — ask the running service

Assumptions about wire formats are cheap to make and expensive to be wrong about.
Two in this codebase were checked rather than guessed:

```bash
# A 401 from the security filter chain: zero bytes, no content-type.
curl -s -o /dev/null -w "status=%{http_code} size=%{size_download} type=%{content_type}\n" \
  http://localhost:8080/v1/saves
```

That empty body is why the app's API client wraps response parsing in a
`try/catch` — without it every expired token surfaces as a JSON parse error
instead of an auth problem. The reason lives in `WWW-Authenticate`, not JSON.

The other: enums are **lower-case on the wire** (`"processing"`, not
`"PROCESSING"`), because every one implements `DbEnum`, whose `db()` carries
`@JsonValue`. Confirm from the Java source, not from the TypeScript.

### Depending on a third party — gate it, don't skip it

Mocking yt-dlp proves the parsing and hides everything that matters about the
tool. But a test that hits YouTube on every build will eventually go red because
YouTube rate-limited us, and a suite that cries wolf gets ignored.

The compromise is a test that is real but **opt-in**, in the suite rather than in
someone's shell history:

```java
@EnabledIfEnvironmentVariable(named = "WEAVR_LIVE_YTDLP", matches = "1")
class YtDlpLiveTest { … }
```

It reports as *skipped* rather than absent, so `Tests run: 67 … Skipped: 2` is a
standing reminder that two checks exist and are not running. Two rules make it
worth having:

- **Assert on shape, not on content.** The title of a video will change; that it
  has a non-blank title will not. `YtDlpLiveTest` asserts the fields parse and
  that *at most four* subtitle files were written — the second is what would have
  caught the 29-download bug on the spot.
- **Print what it saw.** A live test that only passes or fails wastes the trip.
  Logging the field values and the first 200 characters of the transcript is how
  the size-versus-prose inversion became visible at all.

The same pattern is the obvious way to add Testcontainers and, later, a real
Gemini call — both are slow or metered, and neither belongs on every build.

---

## What running the real binary found

The extraction cascade had 27 passing tests before yt-dlp was ever installed.
The first run against a real video found **three** defects. This is the clearest
evidence in the repo for the distinction at the top of this page, so it is worth
recording what each one looked like — every one of them was invisible to a
mocked `ExternalProcess`, because in each case the code was self-consistent and
the *world* was different.

**1. `--sub-langs` entries are regexes, and they matched 29 languages.**

The default was `en.*,en`, which reads like "English variants". yt-dlp
synthesises *translated* caption tracks named `<source>-<target>` at download
time, so the pattern expanded to `en, en-ar, en-zh-TW, en-nl, en-orig, en-fr,
en-de, en-ja, en-ru, …` — 29 subtitle downloads for one video, which earned an
`HTTP Error 429` partway through and left the fetch half-done.

The trap is that the probe JSON gives no warning: `en-ar` is **not** a key in it.
The real keys are plain codes (`ar`, `ja`, `ru`) plus `en` and `en-orig`; the
translated names only exist at download time. Reading the JSON would have
confirmed the wrong conclusion. The fix is exact codes — `en,en-orig`.

**2. Ranking caption files by size picks the worse one, every time.**

Selection was "largest `.vtt` wins", on the reasoning that the biggest file is
the most complete. Measured on one real TED talk:

| Track | Raw bytes | Prose after parsing |
|---|---|---|
| `en` (uploaded) | 8,456 | **4,443 chars** |
| `en-orig` (auto-generated) | 43,947 | 4,284 chars |

The auto track is 5.2× the bytes and carries *less* text, because
auto-captions repeat each line in a rolling window and tag every word with
inline timings (`Hear<00:00:19.720><c> that?</c>`). So the heuristic reliably
chose the noisier source. It would also prefer any non-Latin translation over
English outright, since UTF-8 makes the same content larger. Rank on parsed
prose instead — the parser has already stripped exactly the bloat that was
being mistaken for content.

This also validated `VttParser` for the first time: 43,947 bytes of the messiest
real input available collapsed to within 4% of the human transcript.

**3. A non-zero exit does not mean nothing was produced.**

yt-dlp exits non-zero if *any* requested track fails, even when earlier tracks
already wrote complete files. The code threw on the exit code alone, discarding
a transcript it had successfully fetched. Read the output directory first; only
throw when there is genuinely nothing to keep.

**The generalisable part:** all three were *plausible* readings of the
documentation. Testing them meant giving up on asserting against a mock and
asserting against the artefact the real tool leaves behind — files on disk, and
their parsed content.

---

## What running the real binary found, the second time (visual tier, 2026-08-01)

The visual tier was built the same way and checked the same way, except this
time the real binaries were run *during* development rather than after it — and
they overturned two decisions that had already been written down as plan.

**1. The planned filter chain selects zero frames on exactly the content the
tier exists for.**

CLAUDE.md and the phase plan both specify
`select='gt(scene,0.25)',mpdecimate`. Run against a 12-second video that is one
static ingredient card start to finish, it produces **no frames at all**: frame
0 has no predecessor to differ from, and nothing afterwards changes, so scene
detection never fires. An overlay-only recipe Reel is precisely a static card,
so the tier would have silently found nothing on its primary use case while
every mocked test stayed green.

The fix is two extra selector terms — `+eq(n,0)` for the opening frame and
`+not(mod(n,150))` for a periodic sample — with `mpdecimate` left in place to
collapse the duplicates that introduces. Measured on the same fixture: 0 frames
before, 1 after. `OcrLiveTest.sceneDetectionAloneSelectsNothingFromAStaticCard`
runs both chains side by side so this cannot quietly regress.

**2. The obvious preprocessing makes OCR dramatically worse, not better.**

"Upscale, grayscale, CLAHE contrast, adaptive threshold" is the documented
advice, and ffmpeg's nearest equivalent to CLAHE is `histeq`. Applied to a
white-on-near-black card and read by real tesseract 5.5.0, against the same
frame with no contrast stage at all:

| Chain | Words read | Mean confidence | Sample |
|---|---|---|---|
| `scale=iw*2,format=gray` | **16 of 16** | **95** | `400g` `rigatoni` `2` `tbsp` `olive` `oil` |
| `…,histeq` | 7 garbled tokens, one line lost | 22 | `nigatoni`, `(icupiheavy`, `Siclovesiganic` |

The reason is that the advice assumes a photographic background with a gradient
to flatten. Overlay text is high-contrast and bimodal *by design*, and a global
histogram remap crushes exactly that — the rendered frame comes back mid-grey
with hollowed, speckled glyphs. **And it is worse than a quality regression:**
22 is below the escalation floor of 60, so the "recommended" preprocessing would
have spent a Flash vision request on content the plain chain reads perfectly —
burning the scarcest pool in the system to fix damage it had just caused.

The default chain therefore does no contrast work, and `weavr.ocr.frame-filters`
exists so the eval set can add one for genuinely low-contrast sources without a
code change.

**3. The gate's third signal was confirmed on real content, not reasoned about.**

Run against a real 90-second talking-head video with no overlay text, tesseract
returned tokens like `|` at confidence 72, `=` at 91 and `—` at 74. That is the
failure shape that matters: **confident** symbol soup, not a low-confidence
signal anything could filter on. A gate watching per-word confidence alone —
which is what "use tesseract's confidence" naturally means — would pass it
straight to the model, which would then classify the noise into something
plausible. The alphabetic-token-ratio signal is what rejects it, and
`OcrLiveTest.doesNotPassTextlessVideoOffAsAnExtraction` pins that against a
generated noise clip.

**What is different about this round:** two of the three findings are cases where
the *written plan* was wrong, not where the code drifted from it. Nothing in a
code review would have caught them, because the code faithfully implemented what
the plan said. Only the binary knew.

---

## What running against a real database found (Spaces + billing, 2026-08-01)

The pattern from the two sections above repeated on a third layer. Not a process
this time and not an HTTP client, but **transaction semantics** — and the bug it
produced was invisible to 289 passing tests for the same structural reason each
of the earlier ones was: the thing that behaved unexpectedly was mocked away.

**A `try/catch` around a database write does nothing when it runs inside the
caller's transaction.**

`SaveService.create` recorded a `save_added` activity row for a save going into
a Space. `space_activity.save_id` carries a foreign key, and Hibernate had not
issued the `saves` INSERT yet — `persist()` with an application-assigned UUID
defers it to flush — so the activity insert violated the constraint. That much
is an ordinary ordering mistake.

The interesting half is what happened next. `SpaceService.recordActivity` wraps
its insert in a `try/catch` on the explicit principle that metering must never
fail the thing it describes. It caught the exception. It changed nothing:

```
ERROR: insert or update on table "space_activity" violates foreign key constraint
ERROR: current transaction is aborted, commands ignored until end of transaction block
```

**Postgres aborts the entire transaction on any failed statement.** Every
subsequent command — including the ones that had nothing to do with activity —
returned the second error, and `POST /v1/saves` with a `spaceId` returned 500
every single time.

Two fixes, both structural rather than a guard:

- The `save_added` row is written in an **after-commit hook**. That is the right
  answer and not merely a working one: an activity entry should describe
  something that actually happened, so a save that rolls back should leave no
  trace of itself in the feed.
- `recordActivity` is **`@Transactional(REQUIRES_NEW)`**. Its own transaction is
  the only thing that makes "this must never break its caller" true. Any
  swallow-and-log around a database write needs the same, or it is decorative.

**Why no test caught it.** Every test of `SaveService` mocks `JdbcClient` and
`SpaceService`; a mock has no foreign keys and no transaction state, so the
failure could not occur and the useless catch looked correct. This is the
clearest argument in the repo for Testcontainers: process bugs were found by
running the real binary and HTTP bugs by hitting the real API, and this class of
bug needs a real database in exactly the same way.

Two smaller things the same live pass produced:

- **A vote re-sent unchanged was writing a second activity row**, so one user
  action put three lines in a feed documented as "meaningful events only". Read
  before write, and only record when the value actually changed. Not a crash —
  the sort of thing that only looks wrong when you read the output.
- **`POST /v1/saves` had never checked `spaceId` against membership**, so any
  authenticated user could write into any Space whose id they had been shown.
  It had been that way since Phase 1 and no test asked, because until Spaces
  existed there was nothing to be a member of.

---

## Traps

**Two database URLs, and they are not interchangeable.** `WEAVR_DB_URL` is the
transaction pooler (6543); `WEAVR_FLYWAY_URL` is the session pooler (5432).
Flyway takes a session-level advisory lock, which the transaction pooler breaks —
*later*, under concurrency, not on the first migration.

**`--sub-langs` entries are regexes, and `en.*` is not "English variants".** It
matches yt-dlp's synthesised `<source>-<target>` translation tracks, so one
fetch becomes dozens of downloads and an HTTP 429. Use exact codes. The probe
JSON will not warn you — those track names do not appear in it. Full story
[above](#what-running-the-real-binary-found).

**Never mint a test JWT through `/auth/v1/signup`.** It emails the address you
made up, that bounces, and Supabase warns that the project's email-sending
privileges are at risk — which happened on 2026-08-01, off a single fabricated
`@gmail.com` address. Only two confirmation emails had ever been sent from this
project, so one bounce was a 50% bounce rate. Insert into `auth.users` directly
over JDBC with `email_confirmed_at = now()` (there is no service-role key in
`.env`, or the admin API with `email_confirm: true` would also do), then delete
the user afterwards. The signup call buys nothing: you have to touch the table
anyway to confirm the account.

**When you do insert into `auth.users`, set the token columns to `''`, not
NULL.** `confirmation_token`, `recovery_token`, `email_change_token_new`,
`email_change`, `email_change_token_current`, `phone_change`,
`phone_change_token` and `reauthentication_token` are scanned into
non-nullable Go strings by GoTrue. Left NULL, every sign-in for that user fails
with a **500** reading `Database error querying schema` — which reads like the
database is down rather than like one row is malformed, and sends you looking
in entirely the wrong place. Also insert a matching `auth.identities` row, or
the password grant finds no identity to authenticate against. `crypt(password,
gen_salt('bf'))` from pgcrypto (in the `extensions` schema on Supabase)
produces the bcrypt hash GoTrue expects, so no Java bcrypt dependency is
needed.

**Something may already be listening on 8080.** A leftover `spring-boot:run` will
answer `/actuator/health` with a 200 and make you think your new build started.
Check before trusting a health check:

```bash
netstat -ano | grep LISTENING | grep ":8080 "
```

Start on another port rather than killing a process you did not start:
`PORT=8081 ./mvnw spring-boot:run`.

**`$?` after a pipeline is the last command's exit code.** `./mvnw test | grep ERROR | head -5` followed by `echo $?` reports `head`'s status, which is always 0.
Grep for `BUILD SUCCESS`/`BUILD FAILURE` explicitly instead.

**`EXPO_PUBLIC_*` values are inlined at build time.** Changing `app/.env` does
nothing to a bundler that is already running — restart with
`npx expo start --clear`.

**Mockito self-attach warnings are noise.** `Mockito is currently self-attaching…`
and the byte-buddy agent warnings appear on every run and are not failures.

**Stack traces in test output are usually deliberate.** The job runner tests
exercise failure paths, and the runner logs those failures. Read the
`Tests run: … Failures: 0` line, not the traces.

---

## What is not tested

Honest gaps, roughly in order of how much they would cost to discover late.

| Gap | What would close it |
|---|---|
| **Nothing in the app has run on a device.** No screen rendered, no request sent, sign-in never succeeded | `npx expo run:android` with `app/.env` filled in |
| **Only YouTube has been exercised live.** Instagram, TikTok and Reels paths are unproven, and Instagram increasingly requires auth | Run `YtDlpLiveTest` with `WEAVR_LIVE_URL` set to one URL per platform |
| **ASR has never run against a real Groq call.** Download, downmix and transcription are all mocked | `WEAVR_GROQ_API_KEY` set, and a real video with no captions |
| **The OCR thresholds are guesses.** `min-mean-confidence: 60` and friends have never been measured — only sanity-checked against a synthetic card (95) and a textless clip | The thirty-Reel eval set: measure tesseract against Flash and set the floor from data |
| **The visual tier has only read synthetic fixtures.** Real-world text over photographs, motion blur and stylised fonts are untested, and that is where tesseract fails hard rather than gracefully | Same eval set |
| **No integration tests against a real database.** Every SQL statement is validated only by booting the app. **This one has now actually bitten** — see the transaction-abort bug below | Testcontainers with a `pgvector/pgvector` image |
| **`SaveService`'s idempotency-race recovery is probably broken.** It catches the constraint violation and re-reads inside the same transaction Postgres has just aborted, so it would fail rather than return the existing save. Never observed, because the pre-check handles every non-concurrent replay | Two genuinely concurrent requests with the same key, against a real database |
| **Enrichment has never used a real TMDB or Places key.** Request shapes and the match guard are pinned by `MockRestServiceServer`; whether either API answers this way is unknown | Set `WEAVR_TMDB_API_KEY` / `WEAVR_GOOGLE_PLACES_API_KEY` and classify a real film and a real restaurant |
| **Duplicate detection has never compared two real saves.** The 0.15 cosine threshold is argued from the search half's numbers, not measured | Two people saving the same restaurant from different URLs into one Space |
| **The search distance cutoff is calibrated on three saves.** 0.40 sits in a real measured gap, but seven queries against a three-item corpus is not a calibration | A few hundred real saves, then re-measure hits vs misses |
| **Search relevance has no benchmark.** RRF fusion is unit-tested; whether the fused ordering is *good* is unmeasured | A labelled query set, the search analogue of the OCR eval set |
| **No automated contrast check.** The 78-combination audit is a manual script | A test runner in `app/`, then promote the script above |
| **No test covers `JobStore`'s SQL.** The claim query's correctness rests on one manual run | Same Testcontainers setup |
| **The app has no test runner at all.** `buildDetailModel` was executed once via a throwaway `tsc` + node script (and that found a real bug), but nothing re-runs it | Vitest or Jest, plus React Native Testing Library |
| **No screen has been rendered.** Search, save detail and the rebuilt Library all typecheck and bundle; none has been seen | `npx expo run:android` |

The device gap is the cheap one and it unblocks a whole phase. The Testcontainers
gap is the one that will bite quietly: today a typo in a rarely-hit SQL branch
ships undetected — and `JobStore`'s claim query is now the only substantial piece
of the backend whose correctness still rests on a single manual run, which is
exactly the position the cascade was in before yt-dlp was installed.

---

## Before you commit

```bash
cd api && ./mvnw clean verify
cd app && npm run typecheck && npx expo export --platform android
```

If you touched `palettes.ts` or `contrast.ts`, run the contrast audit too.

If you touched anything under `pipeline/ytdlp/` or `pipeline/ocr/`, run the live
tests — `verify` mocks the process away, which is precisely how three defects
survived 27 green tests in the caption path and two wrong *plan* decisions
survived 47 in the visual tier:

```bash
cd api && WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest
cd api && WEAVR_LIVE_OCR=1 ./mvnw test -Dtest=OcrLiveTest    # no network needed
```

If you touched SQL, boot the service against Supabase — `verify` will not catch
a broken query, and neither will `typecheck`.

State results by the level they came from. "Typechecks and bundles" and "works"
are different claims, and only one of them can be made about most of this
repository right now.
