/**
 * Which of the three adapters is live, decided once.
 *
 * Its own module rather than part of `index.ts` so that `PurchasesProvider` can
 * reach the selection without importing the barrel that re-exports the
 * provider — a cycle that Metro tolerates until the day it resolves the two in
 * the other order and hands one of them a half-initialised module.
 */

import { USE_MOCK_DATA } from '@/data/config';
import { createMockPurchasesAdapter } from './mockPurchases';
import { createPurchasesAdapter } from './purchases';
import type { PurchasesAdapter } from './types';

let adapter: PurchasesAdapter | null = null;

/**
 * Selected the same way `@/data` selects a `Repository` and `@/local` selects a
 * store: one switch, read in one place, invisible above it.
 *
 * `createPurchasesAdapter` resolves to `purchases.web.ts` on web (Metro
 * platform extension), so the web bundle never references the native SDK.
 */
export function purchases(): PurchasesAdapter {
  adapter ??= USE_MOCK_DATA ? createMockPurchasesAdapter() : createPurchasesAdapter();
  return adapter;
}
