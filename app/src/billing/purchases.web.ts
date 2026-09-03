/**
 * The web half of the purchase seam: there is no store here.
 *
 * Metro resolves this file in place of `purchases.ts` for `--platform web`, so
 * the web bundle contains no `react-native-purchases` symbols at all — the same
 * arrangement, and the same motivation, as `sqliteStore.web.ts`: headless
 * Chrome on `expo start --web` is this project's entire visual and interaction
 * harness (`docs/testing.md`), and it must not be traded away for a native
 * module the web build cannot use.
 *
 * This adapter is honest rather than fake. Every method that would need a store
 * either reports "unavailable" or throws `PurchasesUnavailableError`, and the
 * paywall renders that as an explanation. A web stub that pretended a purchase
 * had succeeded would put the one claim this whole subsystem must never make —
 * "you have paid" — into the code path with the least verification behind it.
 *
 * The *mock* adapter (`mockPurchases.ts`, selected by `USE_MOCK_DATA`) is what
 * drives the paywall's full success path in Chrome. That one is opt-in, and its
 * fakery is the point.
 */

import { PurchasesUnavailableError, type PurchasesAdapter } from './types';

const REASON = 'Weavr Pro is bought through the App Store or Google Play, so it is not available on the web.';

export function createPurchasesAdapter(): PurchasesAdapter {
  return {
    kind: 'web',
    available: false,
    unavailableReason: REASON,

    async configure() {
      // Nothing to start.
    },
    async identify() {
      // No SDK identity to attach.
    },
    async signOut() {
      // Nothing to detach.
    },
    async getPlans() {
      throw new PurchasesUnavailableError(REASON);
    },
    async purchase() {
      throw new PurchasesUnavailableError(REASON);
    },
    async restore() {
      throw new PurchasesUnavailableError(REASON);
    },
  };
}
