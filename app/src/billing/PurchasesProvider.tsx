import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import type { MeResponse } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { useSession } from '@/auth/SessionProvider';
import { KV, getStore } from '@/local';
import { sync } from '@/local/sync';
import { purchases } from './adapter';
import {
  PurchasesUnavailableError,
  type PurchasePlan,
  type PurchasesAdapter,
} from './types';

/**
 * How long to wait for the server to agree that a purchase happened, and on
 * what schedule.
 *
 * RevenueCat's webhook normally lands within a couple of seconds, but "normally"
 * is not a guarantee and the delivery is explicitly at-least-once and unordered
 * (`BillingService`). So the wait is bounded and its expiry is a *state*, not a
 * failure: `pending` means the store took the money and the server has not
 * caught up yet, which is true, recoverable, and must never be shown as an
 * error. Total ≈ 21s.
 */
const CONFIRM_BACKOFF_MS = [1000, 1500, 2000, 3000, 4000, 5000, 5000] as const;

export type PlansState = 'loading' | 'ready' | 'empty' | 'error' | 'unavailable';

/**
 * What a purchase or restore actually did.
 *
 * The three outcomes are deliberately separate, because the honest message
 * differs for each and collapsing any two of them produces a lie:
 * - `confirmed` — the server says Pro. The only case that may claim success.
 * - `pending` — the store says paid, the server does not know yet. The user
 *   has been charged; saying "failed" here would be false and alarming.
 * - `cancelled` / `nothing` — nothing happened, and nothing went wrong.
 */
export type PurchaseOutcome =
  | { kind: 'confirmed' }
  | { kind: 'pending' }
  | { kind: 'cancelled' }
  | { kind: 'nothing' };

export interface PurchasesContextValue {
  /** Which adapter is live. The paywall labels itself in mock mode from this. */
  kind: PurchasesAdapter['kind'];
  available: boolean;
  unavailableReason: string | null;

  plans: PurchasePlan[];
  plansState: PlansState;
  /** Why `plansState` is `error`. */
  plansError: string | null;
  reloadPlans: () => void;

  /** True from the moment the store sheet opens until the confirmation wait ends. */
  busy: boolean;
  /** True only during the server-confirmation wait, so the UI can say what it is waiting for. */
  confirming: boolean;

  purchase: (planId: string) => Promise<PurchaseOutcome>;
  restore: () => Promise<PurchaseOutcome>;
}

const PurchasesContext = createContext<PurchasesContextValue | null>(null);

/**
 * Owns the RevenueCat SDK's lifecycle and, more importantly, the gap between it
 * and the server.
 *
 * **The SDK is never asked whether the user is Pro.** It is asked to sell
 * things; `GET /v1/me` is asked who has paid. The two can disagree — a webhook
 * still in flight, a receipt validated on another device — and `MeController`'s
 * own javadoc explains why the server's answer is the one that governs. This
 * provider is where that principle turns into code: every successful purchase
 * is followed by polling `/v1/me` until it agrees, and the UI only ever renders
 * the server's verdict.
 */
export function PurchasesProvider({ children }: { children: React.ReactNode }) {
  const { session } = useSession();
  const adapter = useMemo(() => purchases(), []);
  const userId = session?.user.id ?? null;

  const [plans, setPlans] = useState<PurchasePlan[]>([]);
  const [plansState, setPlansState] = useState<PlansState>(
    adapter.available ? 'loading' : 'unavailable',
  );
  const [plansError, setPlansError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  // The SDK identity, so a session change only calls `logIn` when the user
  // genuinely changed. `logIn` is cheap but not free, and this effect also runs
  // on every token refresh — `onAuthStateChange` fires then, with the same user.
  const identified = useRef<string | null>(null);

  useEffect(() => {
    if (!adapter.available) return;
    let cancelled = false;

    void (async () => {
      try {
        await adapter.configure(userId);
        if (cancelled) return;

        if (userId && identified.current !== userId) {
          await adapter.identify(userId);
          identified.current = userId;
        } else if (!userId && identified.current !== null) {
          await adapter.signOut();
          identified.current = null;
        }
      } catch (error) {
        // A failure here disables buying, not the app. The paywall is the only
        // screen that cares, and it reports the state it finds rather than
        // this exception.
        console.warn('[billing] Could not attach the purchase SDK', error);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [adapter, userId]);

  // ---- the offering -----------------------------------------------------

  useEffect(() => {
    if (!adapter.available) {
      setPlansState('unavailable');
      return;
    }
    let cancelled = false;
    setPlansState('loading');
    setPlansError(null);

    void (async () => {
      try {
        const loaded = await adapter.getPlans();
        if (cancelled) return;
        setPlans(loaded);
        // No packages is a dashboard state — the offering has not been
        // configured yet — not a failure of this app. It gets its own state so
        // the paywall can say so instead of spinning forever.
        setPlansState(loaded.length > 0 ? 'ready' : 'empty');
      } catch (error) {
        if (cancelled) return;
        if (error instanceof PurchasesUnavailableError) {
          setPlansState('unavailable');
          return;
        }
        setPlansError(
          error instanceof Error ? error.message : 'Could not reach the store.',
        );
        setPlansState('error');
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [adapter, reloadToken]);

  const reloadPlans = useCallback(() => setReloadToken((n) => n + 1), []);

  // ---- the server's verdict ---------------------------------------------

  /**
   * Polls `GET /v1/me` until it reports Pro, or the schedule runs out.
   *
   * Goes through `sync.syncMe()` rather than calling the endpoint directly so
   * the answer lands in the local store on the way past — which is what makes
   * the Settings plan card update itself, through `useLiveValue`, with no
   * plumbing between the two screens.
   */
  const waitForServer = useCallback(async (): Promise<boolean> => {
    for (const wait of CONFIRM_BACKOFF_MS) {
      await new Promise((resolve) => setTimeout(resolve, wait));
      try {
        await sync.syncMe();
        const me = await getStore().readKv<MeResponse>(KV.me);
        if (me?.pro) return true;
      } catch {
        // Offline, or the API is briefly unreachable. Keep waiting — the
        // purchase is safe on the store's side either way, and giving up early
        // would report `pending` when the next attempt might have confirmed.
      }
    }
    // Quantifies how often users hit the uncomfortable "still confirming"
    // state — see §E. The user has been charged; this is a wait, not a
    // failure, and the event says so.
    track(AnalyticsEvent.PurchasePollTimeout, {});
    return false;
  }, []);

  const purchase = useCallback(
    async (planId: string): Promise<PurchaseOutcome> => {
      setBusy(true);
      try {
        const result = await adapter.purchase(planId);
        if (result === 'cancelled') return { kind: 'cancelled' };

        setConfirming(true);
        return (await waitForServer()) ? { kind: 'confirmed' } : { kind: 'pending' };
      } finally {
        setConfirming(false);
        setBusy(false);
      }
    },
    [adapter, waitForServer],
  );

  const restore = useCallback(async (): Promise<PurchaseOutcome> => {
    setBusy(true);
    try {
      const found = await adapter.restore();
      if (!found) {
        // Before concluding there is nothing: the server may already know about
        // a subscription this device's store account cannot see (bought on
        // another platform). One cheap check beats telling a paying user they
        // have nothing.
        await sync.syncMe();
        const me = await getStore().readKv<MeResponse>(KV.me);
        return me?.pro ? { kind: 'confirmed' } : { kind: 'nothing' };
      }

      setConfirming(true);
      return (await waitForServer()) ? { kind: 'confirmed' } : { kind: 'pending' };
    } finally {
      setConfirming(false);
      setBusy(false);
    }
  }, [adapter, waitForServer]);

  const value = useMemo(
    () => ({
      kind: adapter.kind,
      available: adapter.available,
      unavailableReason: adapter.unavailableReason,
      plans,
      plansState,
      plansError,
      reloadPlans,
      busy,
      confirming,
      purchase,
      restore,
    }),
    [adapter, plans, plansState, plansError, reloadPlans, busy, confirming, purchase, restore],
  );

  return <PurchasesContext.Provider value={value}>{children}</PurchasesContext.Provider>;
}

export function usePurchases(): PurchasesContextValue {
  const ctx = useContext(PurchasesContext);
  if (!ctx) throw new Error('usePurchases must be used inside <PurchasesProvider>');
  return ctx;
}
