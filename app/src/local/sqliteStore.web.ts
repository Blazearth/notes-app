/**
 * The web half of the storage seam: there is no SQLite here.
 *
 * Metro resolves this file in place of `sqliteStore.ts` for `--platform web`,
 * which is the whole point — importing `expo-sqlite` into the web bundle drags
 * in a WASM path that is labelled alpha and needs
 * `Cross-Origin-Embedder-Policy` / `Cross-Origin-Opener-Policy` headers for
 * `SharedArrayBuffer`. Headless Chrome on `expo start --web` is this project's
 * entire visual and interaction test harness (`docs/testing.md`), so trading it
 * for a storage backend the web build does not need would be a bad deal.
 *
 * Returning `null` makes `@/local/index` fall through to `memoryStore`, which
 * persists an AsyncStorage snapshot and therefore still exercises the real
 * cold-start-from-cache path.
 */

import type { LocalStore } from './store';

export function createSqliteStore(): LocalStore | null {
  return null;
}
