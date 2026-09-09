import AsyncStorage from '@react-native-async-storage/async-storage';
import { createClient } from '@supabase/supabase-js';

import {
  SUPABASE_ANON_KEY_OR_PLACEHOLDER,
  SUPABASE_URL_OR_PLACEHOLDER,
} from '@/api/config';

/**
 * The Supabase client, configured for React Native.
 *
 * Three settings matter here:
 * - `storage: AsyncStorage` — the default is `localStorage`, which does not
 *   exist on native, so without this the session is lost on every cold start.
 * - `detectSessionInUrl: false` — that is an OAuth-redirect behaviour for the
 *   web and throws on native. Because of this, a returning email-verification
 *   link is never picked up automatically — `AuthCallbackScreen` (routed at
 *   `/auth/callback`) does that by hand, via `exchangeCodeForSession`.
 * - `autoRefreshToken` — access tokens are short-lived. It is on by default, but
 *   the refresh timer must be stopped while the app is backgrounded (see
 *   `SessionProvider`), otherwise it fires against a suspended JS runtime.
 * - `flowType: 'pkce'` — required for `exchangeCodeForSession` to work at all.
 *   `signUp`'s `emailRedirectTo` (see `SessionProvider.signUp`) makes the
 *   confirmation email redirect to `/auth/callback?code=...` instead of the
 *   implicit flow's URL-fragment tokens, which `detectSessionInUrl: false`
 *   would otherwise leave stranded with nothing to read them.
 *
 * When the iOS share extension lands, the session will also have to reach a
 * shared **Keychain access group** — the extension is a separate process and
 * cannot read AsyncStorage. That means storing the *refresh* token, not just the
 * access token; see `app/README.md`.
 */
export const supabase = createClient(SUPABASE_URL_OR_PLACEHOLDER, SUPABASE_ANON_KEY_OR_PLACEHOLDER, {
  auth: {
    storage: AsyncStorage,
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: false,
    flowType: 'pkce',
  },
});

/**
 * The AsyncStorage key supabase-js persists the session under — reproduced
 * here, not read off the client, because `storageKey` is `protected` on
 * `GoTrueClient`.
 *
 * Verified against `@supabase/supabase-js`'s own source
 * (`dist/index.mjs`): with no `storageKey` override (this client sets none),
 * the default is `` `sb-${new URL(url).hostname.split('.')[0]}-auth-token` ``,
 * and `_saveSession` (no `userStorage` configured here either) writes the
 * `Session` object to it verbatim via `JSON.stringify` — no wrapper, no
 * separate user record.
 *
 * `SessionProvider` reads this key directly so the splash gate can hydrate
 * from what is already on disk. `supabase.auth.getSession()` looks like the
 * same local read but is not: it awaits `_recoverAndRefresh()`, which awaits
 * a real `/token` network call whenever the stored access token is inside
 * GoTrue's expiry margin — true on most cold starts, since access tokens are
 * short-lived. Blocking the splash on that violates the "app must not depend
 * on the network to launch" rule the rest of `@/local` was built around.
 */
export const SUPABASE_AUTH_STORAGE_KEY = `sb-${new URL(SUPABASE_URL_OR_PLACEHOLDER).hostname.split('.')[0]}-auth-token`;
