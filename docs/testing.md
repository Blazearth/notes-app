# Testing guide

How to check work in this repo, what each check actually proves, and which
mistakes it cannot catch.

The distinction that matters here: **compiling is not running, and running is not
running against the real thing.** Most of this codebase currently sits at the
first two levels, so being precise about which level a claim comes from is the
difference between a useful status and a misleading one.

---

## What each layer proves

| Check | Proves | Does **not** prove |
|---|---|---|
| `./mvnw test` | Logic, error classification, backoff maths, process handling | That any SQL is valid, or that Spring can wire the context |
| `./mvnw clean verify` | The above, plus the jar builds | Anything about the database |
| Booting the API | Context wiring, every bean, Flyway, both pooler URLs | That endpoints behave correctly |
| `curl` against a running API | Real request/response shapes and status codes | Anything on a device |
| `tsc --noEmit` (app) | Types line up, including against the Java DTOs | That a single screen renders |
| `expo export` (app) | Every module resolves; the bundle builds | Same — nothing has run |
| `WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest` | That yt-dlp accepts our argument lists, and the JSON/subtitle files are shaped as assumed | Nothing about the app |
| `expo run:android` | **Actual runtime.** Nothing below this line has ever happened | — |

Nothing in the app has passed the last row yet. Treat every app-side claim
accordingly.

### External binaries

The cascade shells out to yt-dlp and ffmpeg. Neither is a Java dependency, so
Maven will not tell you they are missing — the symptom is every save retrying
until it exhausts `max_attempts`.

```bash
yt-dlp --version && ffmpeg -version | head -1
```

On the dev machine both live in `C:\Users\Saksham\tools\bin`, on the user PATH:
yt-dlp is the standalone `.exe` from GitHub releases, ffmpeg the gyan.dev
"essentials" build. That build is statically linked, hence ~94 MB per binary —
**do not copy that approach into the Docker image**; use the distro package.
Override the binary location with `WEAVR_YTDLP_BINARY` if it is not on PATH.

---

## Running the checks

### Backend

```bash
cd api
./mvnw test           # 67 tests, ~15s, NO database or .env needed (2 skip: see below)
./mvnw clean verify   # the above plus packaging
```

The two skipped tests are `YtDlpLiveTest`, which talks to YouTube. They are
opt-in rather than default because a red build caused by a third party
rate-limiting us is worse than no signal:

```bash
WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest
WEAVR_LIVE_YTDLP=1 WEAVR_LIVE_URL=https://... ./mvnw test -Dtest=YtDlpLiveTest
```

Run them after touching anything under `pipeline/ytdlp/`, and expect an
occasional 429 that is not your fault.

Unit tests need no configuration at all — there is no `@SpringBootTest`, so no
context loads and no environment variables are read. If a test ever starts
needing `.env`, that is a signal it has become an integration test and should be
named like one.

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

---

## Techniques worth reusing

Three things in this repo could not be tested the obvious way. The workarounds
generalise.

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

## Traps

**Two database URLs, and they are not interchangeable.** `WEAVR_DB_URL` is the
transaction pooler (6543); `WEAVR_FLYWAY_URL` is the session pooler (5432).
Flyway takes a session-level advisory lock, which the transaction pooler breaks —
*later*, under concurrency, not on the first migration.

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
| **ffmpeg is installed but nothing calls it.** ASR and keyframe extraction are unwritten, so the binary is staged, not used | Phase 2 ASR / Phase 4 keyframes |
| **No integration tests against a real database.** Every SQL statement is validated only by booting the app | Testcontainers with a `pgvector/pgvector` image |
| **No automated contrast check.** The 78-combination audit is a manual script | A test runner in `app/`, then promote the script above |
| **No test covers `JobStore`'s SQL.** The claim query's correctness rests on one manual run | Same Testcontainers setup |
| **The app has no test runner at all** | Vitest or Jest, plus React Native Testing Library |

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

If you touched anything under `pipeline/ytdlp/`, run the live test —
`verify` mocks the process away, which is precisely how three defects survived
27 green tests:

```bash
cd api && WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest
```

If you touched SQL, boot the service against Supabase — `verify` will not catch
a broken query, and neither will `typecheck`.

State results by the level they came from. "Typechecks and bundles" and "works"
are different claims, and only one of them can be made about most of this
repository right now.
