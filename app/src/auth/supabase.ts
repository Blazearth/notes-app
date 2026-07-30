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
 *   web and throws on native.
 * - `autoRefreshToken` — access tokens are short-lived. It is on by default, but
 *   the refresh timer must be stopped while the app is backgrounded (see
 *   `SessionProvider`), otherwise it fires against a suspended JS runtime.
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
  },
});
