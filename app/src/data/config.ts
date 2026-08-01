/**
 * The one switch.
 *
 * `true`  — the whole app runs on `MockRepository`. No API calls, no database,
 *           no Supabase session required: the app opens straight onto Home with
 *           realistic content, so the UI can be built and reviewed with no
 *           backend running at all.
 * `false` — every screen talks to the real Spring API through `ApiRepository`,
 *           exactly as before. Sign-in is required again.
 *
 * Nothing else in the app reads this. Screens depend on the `Repository`
 * interface and receive whichever implementation `data/index.ts` selects, so
 * moving between development and production is this line and nothing else.
 *
 * Deliberately a plain constant rather than an `EXPO_PUBLIC_*` variable:
 * those are inlined at bundle time and silently differ between a web bundle
 * and a device bundle built from a different shell, which is exactly the kind
 * of ambiguity a development switch must not have.
 */
export const USE_MOCK_DATA = true;

/**
 * How long mock calls pretend to take, in milliseconds.
 *
 * Not zero, on purpose. A repository that resolves synchronously hides every
 * loading state in the app — spinners, skeletons and empty-then-filled
 * transitions all get skipped, and they are precisely the states that are
 * hardest to get right and easiest to forget. This is short enough not to be
 * annoying and long enough that a missing loading state is visible.
 */
export const MOCK_LATENCY_MS = { min: 200, max: 400 } as const;
