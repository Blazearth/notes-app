/**
 * The native half of the purchase seam — the only file in the app that imports
 * `react-native-purchases`.
 *
 * Metro resolves `purchases.web.ts` in place of this file for
 * `--platform web`, which is the whole point: the SDK is a native module with
 * no web presence, and this project's visual harness is headless Chrome on
 * `expo start --web` (`docs/testing.md`). Same split, same reason, as
 * `sqliteStore.ts` / `sqliteStore.web.ts`.
 *
 * Requires a dev client or a store build — `react-native-purchases` is not in
 * Expo Go.
 */

import Purchases, {
  PACKAGE_TYPE,
  PURCHASES_ERROR_CODE,
  type PurchasesError,
  type PurchasesPackage,
} from 'react-native-purchases';

import { ENTITLEMENT_ID, revenueCatApiKey } from './config';
import {
  PurchaseFailedError,
  PurchasesUnavailableError,
  type PurchasePlan,
  type PurchaseResult,
  type PurchasesAdapter,
} from './types';

const PERIOD_BY_PACKAGE_TYPE: Partial<Record<PACKAGE_TYPE, PurchasePlan['period']>> = {
  [PACKAGE_TYPE.MONTHLY]: 'monthly',
  [PACKAGE_TYPE.ANNUAL]: 'annual',
  [PACKAGE_TYPE.LIFETIME]: 'lifetime',
};

/**
 * The packages behind the last `getPlans()`, by plan id.
 *
 * `purchasePackage` wants the SDK's own object, not an identifier, and the
 * paywall only ever holds a `PurchasePlan` — so the mapping has to live
 * somewhere, and it lives here rather than leaking an SDK type through the
 * seam that exists to keep it out.
 */
let packagesById = new Map<string, PurchasesPackage>();

/**
 * RevenueCat's errors arrive as plain objects, not `Error` instances, so
 * `instanceof` is useless on them — shape is the only reliable test.
 */
function asPurchasesError(error: unknown): PurchasesError | null {
  if (typeof error !== 'object' || error === null) return null;
  return 'code' in error && 'message' in error ? (error as PurchasesError) : null;
}

function toPlan(pkg: PurchasesPackage): PurchasePlan {
  const product = pkg.product;
  const period = PERIOD_BY_PACKAGE_TYPE[pkg.packageType] ?? 'other';

  // Only ever derived from what the store itself returned, and only when it
  // adds something the price line does not already say. An annual plan's
  // monthly equivalent is the one comparison people actually make; a monthly
  // plan restating its own price per month is noise.
  let secondary: string | null = null;
  if (period === 'annual' && product.pricePerMonth != null) {
    const perMonth = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: product.currencyCode,
    }).format(product.pricePerMonth);
    secondary = `${perMonth} / month, billed yearly`;
  } else if (period === 'lifetime') {
    secondary = 'One payment, no renewal';
  }

  return {
    id: pkg.identifier,
    title: product.title,
    priceString: product.priceString,
    period,
    secondary,
  };
}

let configured = false;

export function createPurchasesAdapter(): PurchasesAdapter {
  const apiKey = revenueCatApiKey();
  const available = apiKey.length > 0;

  return {
    kind: 'native',
    available,
    unavailableReason: available
      ? null
      : 'This build has no RevenueCat key, so purchases are switched off.',

    async configure(appUserId) {
      if (!available || configured) return;
      // `configure` is synchronous and void — it throws only on an outright
      // bad key. `isConfigured` guards a second call across a fast refresh,
      // where the module-level flag above has been reset but the native SDK
      // has not.
      if (await Purchases.isConfigured()) {
        configured = true;
        return;
      }
      Purchases.configure({ apiKey, appUserID: appUserId ?? undefined });
      configured = true;
    },

    async identify(appUserId) {
      if (!available || !configured) return;
      await Purchases.logIn(appUserId);
    },

    async signOut() {
      if (!available || !configured) return;
      // Anonymous again, so the next account on this device does not inherit
      // this one's receipts. Throws if already anonymous, which is a no-op
      // dressed as a failure — swallow it.
      try {
        await Purchases.logOut();
      } catch {
        // Already anonymous.
      }
    },

    async getPlans() {
      if (!available) throw new PurchasesUnavailableError(this.unavailableReason!);
      const offerings = await Purchases.getOfferings();
      const current = offerings.current;
      // No current offering is a dashboard state, not an error: the app is
      // configured correctly and simply has nothing to sell yet. The paywall
      // renders its own empty case for this.
      const packages = current?.availablePackages ?? [];
      packagesById = new Map(packages.map((pkg) => [pkg.identifier, pkg]));
      return packages.map(toPlan);
    },

    async purchase(planId): Promise<PurchaseResult> {
      if (!available) throw new PurchasesUnavailableError(this.unavailableReason!);
      const pkg = packagesById.get(planId);
      if (!pkg) {
        throw new PurchaseFailedError('That plan is no longer available. Pull to refresh and try again.');
      }

      try {
        await Purchases.purchasePackage(pkg);
        return 'purchased';
      } catch (error) {
        const rc = asPurchasesError(error);
        // Backing out of the store sheet is not a failure and must never be
        // reported as one.
        if (rc?.code === PURCHASES_ERROR_CODE.PURCHASE_CANCELLED_ERROR || rc?.userCancelled) {
          return 'cancelled';
        }
        if (rc?.code === PURCHASES_ERROR_CODE.PRODUCT_ALREADY_PURCHASED_ERROR) {
          // Already owned on this store account — the receipt exists, so this
          // is a restore, not a purchase, and the caller's server-confirmation
          // wait is exactly the right next step.
          return 'purchased';
        }
        throw new PurchaseFailedError(rc?.message ?? 'The store could not complete that purchase.');
      }
    },

    async restore() {
      if (!available) throw new PurchasesUnavailableError(this.unavailableReason!);
      try {
        const info = await Purchases.restorePurchases();
        return info.entitlements.active[ENTITLEMENT_ID] != null;
      } catch (error) {
        const rc = asPurchasesError(error);
        throw new PurchaseFailedError(rc?.message ?? 'Could not reach the store to restore purchases.');
      }
    },
  };
}
