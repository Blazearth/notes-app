/**
 * The billing layer's entry point.
 *
 * ```
 * PaywallScreen ── usePurchases() ──► PurchasesProvider ──► PurchasesAdapter
 *                                            │                    │
 *                                            │              native / web / mock
 *                                            └── sync.syncMe() ──► GET /v1/me
 * ```
 *
 * The right-hand side buys things. The bottom edge is what decides whether the
 * user is Pro, and it is the *only* thing that does — see
 * `PurchasesProvider`'s class comment and `MeController`'s on the server.
 */

export type {
  PlanPeriod,
  PurchasePlan,
  PurchaseResult,
  PurchasesAdapter,
} from './types';
export { PurchaseFailedError, PurchasesUnavailableError } from './types';
export { purchases } from './adapter';
export {
  PurchasesProvider,
  usePurchases,
  type PlansState,
  type PurchaseOutcome,
  type PurchasesContextValue,
} from './PurchasesProvider';
