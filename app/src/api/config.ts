/**
 * Runtime configuration.
 *
 * `EXPO_PUBLIC_*` variables are inlined into the JS bundle at build time, so
 * everything here is public by definition. That is correct for all three: the
 * Supabase URL and **anon** key are designed to ship inside client binaries
 * (row-level security and the API's own authorization are what protect data),
 * and the API base URL is not a secret either.
 *
 * The service-role key must never appear in this file or anywhere under `app/`.
 *
 * Missing values are *collected*, not thrown. Throwing at module scope would run
 * before React mounts, so a fresh clone with no `.env` would show a white screen
 * instead of saying which variable is missing — `_layout` renders
 * `ConfigErrorScreen` off `MISSING_CONFIG` instead.
 */

export const SUPABASE_URL = process.env.EXPO_PUBLIC_SUPABASE_URL ?? '';
export const SUPABASE_ANON_KEY = process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY ?? '';

/**
 * Where Spring Boot is listening.
 *
 * `localhost` does not mean the dev machine on a device or emulator. Android
 * emulators reach the host at `10.0.2.2`; a physical device needs the machine's
 * LAN IP. There is deliberately no default, because a wrong default fails as an
 * opaque network timeout rather than a clear misconfiguration.
 */
export const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? '';

export const MISSING_CONFIG: string[] = (
  [
    ['EXPO_PUBLIC_SUPABASE_URL', SUPABASE_URL],
    ['EXPO_PUBLIC_SUPABASE_ANON_KEY', SUPABASE_ANON_KEY],
    ['EXPO_PUBLIC_API_BASE_URL', API_BASE_URL],
  ] as const
)
  .filter(([, value]) => !value)
  .map(([name]) => name);

/**
 * `createClient` rejects an empty URL, and it is constructed at module load — so
 * when configuration is missing we hand it something syntactically valid and
 * never call it, because the app renders the config error screen instead.
 */
export const SUPABASE_URL_OR_PLACEHOLDER = SUPABASE_URL || 'https://placeholder.supabase.co';
export const SUPABASE_ANON_KEY_OR_PLACEHOLDER = SUPABASE_ANON_KEY || 'placeholder-anon-key';
