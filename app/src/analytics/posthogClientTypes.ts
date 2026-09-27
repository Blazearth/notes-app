/**
 * The purchase-seam pattern (`@/billing/types`) applied to analytics: nothing
 * above this file may import `posthog-react-native` or `posthog-js` directly,
 * so the web bundle never references a native module and `client.ts` never
 * learns which of the two implementations (`posthogClient.ts` native,
 * `posthogClient.web.ts` web) it is talking to.
 */
/** Every event property in `events.ts` is a string, number, or boolean — see its header comment. */
export type AnalyticsPropertyValue = string | number | boolean;

export interface PosthogClient {
  init(apiKey: string, host: string): void;
  identify(userId: string): void;
  reset(): void;
  capture(event: string, properties: Record<string, AnalyticsPropertyValue>): void;
}
