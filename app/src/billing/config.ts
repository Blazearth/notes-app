/**
 * RevenueCat's public SDK keys.
 *
 * These are *public* keys — they identify the app to RevenueCat and are meant
 * to ship inside the binary, unlike the webhook secret (server-side only, see
 * `.env.example`) and unlike anything that could grant an entitlement. Nothing
 * a user could do with one of these bypasses `EntitlementService`, because the
 * client is never asked whether it is Pro.
 *
 * **Absent disables purchases; it does not break the app** — the same
 * "unset disables rather than opens" rule the RevenueCat webhook secret and
 * the Groq/RapidAPI keys already follow. They are deliberately *not* in
 * `MISSING_CONFIG` (`@/api/config`): a build without them should run
 * completely, minus a paywall that explains itself, rather than land every
 * user on `ConfigErrorScreen`.
 *
 * Like every `EXPO_PUBLIC_*` value these are inlined at bundle time, so a
 * change needs a rebuild, not a restart — see `ConfigErrorScreen`'s note.
 */

import { Platform } from 'react-native';

export const REVENUECAT_IOS_KEY = process.env.EXPO_PUBLIC_REVENUECAT_IOS_KEY ?? '';
export const REVENUECAT_ANDROID_KEY = process.env.EXPO_PUBLIC_REVENUECAT_ANDROID_KEY ?? '';

/** The key for the platform actually running, or '' when this platform has none. */
export function revenueCatApiKey(): string {
  return Platform.select({
    ios: REVENUECAT_IOS_KEY,
    android: REVENUECAT_ANDROID_KEY,
    default: '',
  });
}

/**
 * The entitlement identifier, matching `weavr.billing.entitlement-id` on the
 * server (`application.yml`, default `pro`).
 *
 * The client only uses this to read the SDK's own `customerInfo` when deciding
 * whether a *restore* found anything worth waiting on. It is never the basis
 * for unlocking a feature — that is the server's answer, always.
 */
export const ENTITLEMENT_ID = process.env.EXPO_PUBLIC_ENTITLEMENT_ID ?? 'pro';
