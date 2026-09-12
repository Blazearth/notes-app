/**
 * PostHog's public project key, and where to send events.
 *
 * Like `EXPO_PUBLIC_REVENUECAT_*` (see `@/billing/config`), this is inlined
 * into the JS bundle at build time and is not a secret — it identifies the
 * project, not a credential. **Absent disables analytics; it does not break
 * the app** — the same "unset disables rather than opens" rule every other
 * optional integration here follows.
 */

export const POSTHOG_KEY = process.env.EXPO_PUBLIC_POSTHOG_KEY ?? '';
export const POSTHOG_HOST = process.env.EXPO_PUBLIC_POSTHOG_HOST ?? 'https://us.i.posthog.com';

/**
 * Overrides the `__DEV__` no-op so a local build can be checked against a
 * real PostHog project without shipping that build. Never set this in a
 * production `eas.json` profile.
 */
export const ANALYTICS_DEBUG = process.env.EXPO_PUBLIC_ANALYTICS_DEBUG === 'true';
