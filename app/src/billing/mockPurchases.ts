/**
 * The purchase seam under `USE_MOCK_DATA` — no store, no network, a real loop.
 *
 * This exists for the same reason `mockRepository` does, and follows the same
 * rule that file states: **the mock is stateful, not a fixture dump.** A mock
 * purchase actually flips the mock account to Pro (`grantMockPro`), so the
 * paywall's whole sequence — buy, wait for the server to confirm, watch the
 * Settings plan card become "Weavr Pro" — runs end to end in headless Chrome
 * against `expo start --web`. A mock that resolved `true` and changed nothing
 * would leave the confirmation wait, the polling timeout and every state the
 * paywall renders while it waits completely unexercised, which are precisely
 * the states hardest to get right.
 *
 * The latency is deliberate and larger than the repository's, because it is
 * standing in for a store sheet a human interacts with, not an HTTP call.
 */

import { grantMockPro } from '@/data/mockRepository';
import type { PurchasePlan, PurchaseResult, PurchasesAdapter } from './types';

const STORE_SHEET_MS = 900;

/**
 * Shaped like real RevenueCat output, not like whatever the paywall wanted —
 * two packages, store-formatted prices, an annual plan whose secondary line is
 * the per-month comparison the real adapter derives from `pricePerMonth`. The
 * UI therefore renders these through exactly the branches production hits.
 */
const MOCK_PLANS: PurchasePlan[] = [
  {
    id: '$rc_annual',
    title: 'Weavr Pro (Annual)',
    priceString: '£29.99',
    period: 'annual',
    secondary: '£2.50 / month, billed yearly',
  },
  {
    id: '$rc_monthly',
    title: 'Weavr Pro (Monthly)',
    priceString: '£3.99',
    period: 'monthly',
    secondary: null,
  },
];

function delay<T>(value: T, ms: number): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

export function createMockPurchasesAdapter(): PurchasesAdapter {
  return {
    kind: 'mock',
    available: true,
    unavailableReason: null,

    async configure() {
      // Nothing to start.
    },
    async identify() {
      // Nothing to attach.
    },
    async signOut() {
      // Nothing to detach.
    },

    getPlans(): Promise<PurchasePlan[]> {
      return delay(MOCK_PLANS, 350);
    },

    async purchase(planId): Promise<PurchaseResult> {
      await delay(null, STORE_SHEET_MS);
      // What the real webhook does, minus the webhook: the *server's* answer
      // to "is this user Pro" changes, which is the only thing the app is
      // allowed to believe. The provider still has to poll `/v1/me` to find
      // out, exactly as it does in production.
      grantMockPro(planId);
      return 'purchased';
    },

    async restore(): Promise<boolean> {
      await delay(null, 600);
      // Nothing was ever bought on this fake store account, so the honest
      // answer is "nothing to restore" — which is the branch worth being able
      // to look at, since it is the one users hit by mistake.
      return false;
    },
  };
}
