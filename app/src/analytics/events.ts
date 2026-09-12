/**
 * The client-side event taxonomy — see `docs/weavr-analytics-plan.md` §E.
 *
 * Capture/extraction/save events (`capture_received`, `extraction_completed`,
 * `save_ready`, etc.) are emitted **server-side** (Spring Boot) because most
 * captures never run through this JS layer at all — Android's silent
 * share-intent receiver enqueues via `CaptureStore`/WorkManager while this
 * SDK is not running. They are not listed here; see
 * `api/src/main/java/com/weavr/api/analytics/AnalyticsEvents.java`.
 *
 * Every property type below is an enum-like union, a number, or a boolean —
 * never a free string — so a raw content field structurally cannot compile
 * into an event body. This is §O's promise made real: don't widen a type to
 * `string` here without checking it against §G's "should never collect" list.
 */

import type { ApiErrorKind } from '@/api/client';

export const AnalyticsEvent = {
  AppOpened: 'app_opened',
  SignInCompleted: 'sign_in_completed',
  SaveViewed: 'save_viewed',
  SaveDeleted: 'save_deleted',
  SearchPerformed: 'search_performed',
  SearchFailed: 'search_failed',
  SearchResultOpened: 'search_result_opened',
  ActStarted: 'act_started',
  ActCompleted: 'act_completed',
  GroupOpened: 'group_opened',
  CollectionOpened: 'collection_opened',
  SpaceCreated: 'space_created',
  SpaceJoined: 'space_joined',
  PaywallViewed: 'paywall_viewed',
  PurchaseStarted: 'purchase_started',
  PurchasePollTimeout: 'purchase_poll_timeout',
  ScreenViewed: 'screen_viewed',
} as const;

/** Bucketed, never the raw age — see §E's `save_deleted` row. */
export type AgeSinceCaptureBucket = '<1h' | '<1d' | '<1w' | '>1w';

/** The two Acts that exist today — see §J's funnels. */
export type ActType = 'shopping_list' | 'compare_workouts';

/** Scoped to Home/Library/Spaces/Search/Paywall/Settings only — see §G. */
export type ScreenName = 'home' | 'library' | 'spaces' | 'search' | 'paywall' | 'settings';

export type PaywallTrigger = 'entitlement_gate' | 'settings';

export interface AnalyticsEventProperties {
  [AnalyticsEvent.AppOpened]: { startup_duration_ms?: number };
  [AnalyticsEvent.SignInCompleted]: Record<string, never>;
  [AnalyticsEvent.SaveViewed]: { knowledge_type: string; days_since_capture: number };
  [AnalyticsEvent.SaveDeleted]: { age_since_capture: AgeSinceCaptureBucket };
  [AnalyticsEvent.SearchPerformed]: { result_count: number; has_results: boolean };
  [AnalyticsEvent.SearchFailed]: { error_type: ApiErrorKind | 'unknown' };
  [AnalyticsEvent.SearchResultOpened]: { position: number; knowledge_type: string };
  [AnalyticsEvent.ActStarted]: { act_type: ActType };
  [AnalyticsEvent.ActCompleted]: { act_type: ActType };
  [AnalyticsEvent.GroupOpened]: { group_key: string };
  [AnalyticsEvent.CollectionOpened]: { collection_type: string };
  [AnalyticsEvent.SpaceCreated]: { has_template: boolean };
  [AnalyticsEvent.SpaceJoined]: Record<string, never>;
  [AnalyticsEvent.PaywallViewed]: { trigger: PaywallTrigger };
  [AnalyticsEvent.PurchaseStarted]: { package_id: string };
  [AnalyticsEvent.PurchasePollTimeout]: Record<string, never>;
  [AnalyticsEvent.ScreenViewed]: { screen_name: ScreenName };
}

export type AnalyticsEventName = keyof AnalyticsEventProperties;

/** §E's `save_deleted` bucket — fast deletion proxies bad extraction, slow deletion is normal cleanup. */
export function ageSinceCaptureBucket(createdAtIso: string): AgeSinceCaptureBucket {
  const ms = Date.now() - new Date(createdAtIso).getTime();
  const hour = 3_600_000;
  if (ms < hour) return '<1h';
  if (ms < 24 * hour) return '<1d';
  if (ms < 7 * 24 * hour) return '<1w';
  return '>1w';
}

/** §E's `save_viewed` — bucketed as a day count, never a raw timestamp. */
export function daysSinceCapture(createdAtIso: string): number {
  const ms = Date.now() - new Date(createdAtIso).getTime();
  return Math.max(0, Math.floor(ms / (24 * 3_600_000)));
}
