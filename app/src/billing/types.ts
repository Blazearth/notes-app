/**
 * The purchase seam's contract — deliberately free of any RevenueCat type.
 *
 * `react-native-purchases` is a native module. It does not exist in the web
 * bundle, and headless Chrome on `expo start --web` is this project's entire
 * visual and interaction test harness (`docs/testing.md`). So nothing above
 * this file may import the SDK: the paywall renders `PurchasePlan`, catches
 * `PurchasesUnavailableError`, and never learns which of the three adapters
 * (`purchases.ts` native, `purchases.web.ts` stub, `mockPurchases.ts`) it is
 * talking to — exactly the split `LocalStore` already uses for SQLite, and
 * `Repository` for the API.
 *
 * **This layer buys the purchase, it does not decide entitlement.** Whether a
 * user is Pro is `GET /v1/me`'s answer and nothing else — see
 * `MeController`'s javadoc, and `PurchasesProvider.purchase` for what happens
 * in the window between the store saying yes and the webhook landing.
 */

/** How a plan recurs. `other` covers anything the store offers that we don't name. */
export type PlanPeriod = 'monthly' | 'annual' | 'lifetime' | 'other';

/**
 * One purchasable plan, flattened out of RevenueCat's
 * `PurchasesPackage`/`PurchasesStoreProduct` pair.
 *
 * `priceString` is the store's own localised string (`£3.99`), never a number
 * we format ourselves — currency, placement and separators are the store's to
 * decide, and a hand-built price is how an app ends up showing `$3.99` in a
 * country that pays in rupees.
 */
export interface PurchasePlan {
  /** The package identifier. What `purchase()` takes back. */
  id: string;
  /** Product title from the store listing. */
  title: string;
  /** Localised, store-formatted price. Render verbatim. */
  priceString: string;
  period: PlanPeriod;
  /**
   * A second line under the price — "≈ £2.49 / month", a trial note. Null
   * when the store gave us nothing worth adding, so the UI renders no
   * placeholder rather than an empty row.
   */
  secondary: string | null;
}

/**
 * What a purchase attempt did.
 *
 * `cancelled` is a first-class outcome rather than an error, because it is not
 * one: a user who backs out of the store sheet has done nothing wrong and must
 * not be shown a failure. Every other problem throws.
 */
export type PurchaseResult = 'purchased' | 'cancelled';

/**
 * Purchases cannot run here at all — no store, no configured API key, or the
 * web bundle where the native module does not exist.
 *
 * Distinct from a failed purchase on purpose: the paywall answers this by
 * explaining *why* buying is not possible, rather than offering a button that
 * throws.
 */
export class PurchasesUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PurchasesUnavailableError';
  }
}

/** A store or network failure during a purchase or restore. */
export class PurchaseFailedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PurchaseFailedError';
  }
}

export interface PurchasesAdapter {
  /** Which implementation this is. Surfaced on the paywall in mock mode only. */
  readonly kind: 'native' | 'web' | 'mock';

  /**
   * Whether purchases can be attempted. False disables the buy buttons and
   * shows the reason — it never throws on its own.
   */
  readonly available: boolean;

  /** Why `available` is false. Null when it is true. */
  readonly unavailableReason: string | null;

  /**
   * Starts the SDK, at most once per process.
   *
   * @param appUserId the Supabase user id, or null when signed out. This
   *   becomes RevenueCat's `app_user_id`, which is what `BillingService`
   *   parses as a UUID to attribute the webhook — see that class. Null
   *   configures anonymously, and the events that produces are skipped
   *   server-side as `unknown_user` until `identify` runs.
   */
  configure(appUserId: string | null): Promise<void>;

  /** Attaches the SDK's identity to a signed-in user. Safe to call repeatedly. */
  identify(appUserId: string): Promise<void>;

  /** Detaches on sign-out, so the next user does not inherit this one's receipts. */
  signOut(): Promise<void>;

  /** The current offering's packages. Empty when the dashboard has no offering configured. */
  getPlans(): Promise<PurchasePlan[]>;

  /** @param planId a `PurchasePlan.id` from the most recent `getPlans()`. */
  purchase(planId: string): Promise<PurchaseResult>;

  /**
   * Re-applies purchases already made by this store account.
   *
   * @returns whether anything came back. False is a real answer ("nothing to
   *   restore"), not a failure, and the UI says so rather than staying silent.
   */
  restore(): Promise<boolean>;
}
