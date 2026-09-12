/**
 * The web half of the analytics seam — see `posthogClient.ts`.
 *
 * `posthog-react-native` pulls in native-module peer dependencies the web
 * bundle cannot use, so web gets PostHog's own browser SDK instead. Session
 * recording is explicitly disabled for the same reason as the native side —
 * see §R of the analytics plan.
 */

import posthog from 'posthog-js';

import type { PosthogClient } from './posthogClientTypes';

let initialized = false;

export const posthogClient: PosthogClient = {
  init(apiKey, host) {
    if (initialized) return;
    initialized = true;
    posthog.init(apiKey, {
      api_host: host,
      autocapture: false,
      capture_pageview: false,
      disable_session_recording: true,
      persistence: 'localStorage',
    });
  },
  identify(userId) {
    posthog.identify(userId);
  },
  reset() {
    posthog.reset();
  },
  capture(event, properties) {
    posthog.capture(event, properties);
  },
};
