/**
 * The one entry point every screen/provider calls — `init`, `identify`,
 * `reset`, and a single typed `track()`. See `docs/weavr-analytics-plan.md`
 * §O for why this file, not a bare `posthog-react-native` import, is what
 * the rest of the app depends on.
 *
 * No-ops (console-logs instead) under `__DEV__` unless `ANALYTICS_DEBUG` is
 * set — otherwise every emulator/Expo-web dev session pollutes production
 * data (§P). Also no-ops, silently, whenever `POSTHOG_KEY` is unset: absent
 * config disables analytics rather than breaking the app.
 */

import { ANALYTICS_DEBUG, POSTHOG_HOST, POSTHOG_KEY } from './config';
import type { AnalyticsEventName, AnalyticsEventProperties } from './events';
import { posthogClient } from './posthogClient';

function devNoOp(): boolean {
  return __DEV__ && !ANALYTICS_DEBUG;
}

let initialized = false;

/** Safe to call every app launch — a second call is a no-op. */
export function initAnalytics(): void {
  if (!POSTHOG_KEY || devNoOp() || initialized) return;
  initialized = true;
  posthogClient.init(POSTHOG_KEY, POSTHOG_HOST);
}

/**
 * Attaches the SDK's identity to a signed-in user — call on every session
 * establish, not just first sign-in (§F: token refresh and app restart both
 * fire `onAuthStateChange` with the same user, and re-identifying is cheap).
 */
export function identify(userId: string): void {
  if (devNoOp()) {
    console.log('[analytics] identify (dev no-op)', userId);
    return;
  }
  if (!POSTHOG_KEY) return;
  posthogClient.identify(userId);
}

/**
 * Call on sign-out. Skipping this is the single most common way a
 * multi-account device ends up with mismerged people in PostHog (§F) — put
 * it in the QA checklist alongside every other sign-out cleanup.
 */
export function resetAnalytics(): void {
  if (devNoOp()) {
    console.log('[analytics] reset (dev no-op)');
    return;
  }
  if (!POSTHOG_KEY) return;
  posthogClient.reset();
}

/**
 * Fully typed against {@link AnalyticsEventProperties} — a free-text property
 * cannot compile in, by construction (§O).
 */
export function track<E extends AnalyticsEventName>(
  event: E,
  properties: AnalyticsEventProperties[E],
): void {
  if (devNoOp()) {
    console.log('[analytics]', event, properties);
    return;
  }
  if (!POSTHOG_KEY) return;
  posthogClient.capture(event, properties);
}
