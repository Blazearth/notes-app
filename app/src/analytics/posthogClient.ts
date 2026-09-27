/**
 * The native half of the analytics seam — `react-native-purchases`'s split
 * (`@/billing/purchases.ts` vs `purchases.web.ts`) applied to PostHog: Metro
 * resolves this file in place of `posthogClient.web.ts` for every platform
 * except `--platform web`.
 *
 * Session replay is never enabled here — Weavr's content is personal
 * screenshots/photos/PDFs, and replay would visually capture exactly what the
 * privacy policy commits to never collecting (§R).
 */

import PostHog from 'posthog-react-native';

import type { PosthogClient } from './posthogClientTypes';

let client: PostHog | null = null;

export const posthogClient: PosthogClient = {
  init(apiKey, host) {
    if (client) return;
    client = new PostHog(apiKey, {
      host,
      enableSessionReplay: false,
    });
  },
  identify(userId) {
    client?.identify(userId);
  },
  reset() {
    client?.reset();
  },
  capture(event, properties) {
    client?.capture(event, properties);
  },
};
