import { useLocalSearchParams, useRouter } from 'expo-router';
import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Linking, View } from 'react-native';

import type { MeResponse } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent, type PaywallTrigger } from '@/analytics/events';
import { usePurchases, type PurchaseOutcome, type PurchasePlan } from '@/billing';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph, type GlyphName } from '@/components/Glyph';
import { Screen } from '@/components/Screen';
import { Touchable } from '@/components/Touchable';
import { KV, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import { PRIVACY_POLICY_URL, hasLegalUrl } from '@/legal/links';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * What Pro actually changes, and nothing else.
 *
 * Every line here maps to a real gate in `UsageService` — 20 AI saves a
 * calendar month, one Act conversion an ISO week, both UTC-bounded. Nothing on
 * this list is aspirational, and the third row exists to say plainly that the
 * rest of the app is not held back, which is true and is the sort of claim a
 * paywall is normally built to avoid making.
 */
const BENEFITS: { icon: GlyphName; title: string; detail: string }[] = [
  {
    icon: 'layers',
    title: 'Unlimited AI saves',
    detail: 'The free plan runs 20 through the pipeline each month.',
  },
  {
    icon: 'list',
    title: 'Unlimited shopping lists',
    detail: 'Turning a recipe into a list is capped at one a week on free.',
  },
  {
    icon: 'members',
    title: 'Everything else is already included',
    detail: 'Spaces, search, collections and your whole library are unlimited on both plans.',
  },
];

function Benefit({ icon, title, detail }: (typeof BENEFITS)[number]) {
  const { palette, spacing, radius } = useTheme();
  return (
    <View style={{ flexDirection: 'row', marginBottom: spacing.lg }}>
      <View
        style={{
          width: 34,
          height: 34,
          borderRadius: radius.sm,
          backgroundColor: palette.accentContainer,
          alignItems: 'center',
          justifyContent: 'center',
          marginRight: spacing.md,
        }}
      >
        <Glyph name={icon} size={17} color={palette.onAccentContainer} />
      </View>
      {/* The text column takes the remaining width along the *row* axis, which
          is the only axis `flex: 1` is safe on in a container of indefinite
          height — see the Capture sheet's Yoga collapse in docs/testing.md. */}
      <View style={{ flex: 1 }}>
        <AppText variant="cardTitle" style={{ fontSize: 14, marginBottom: 2 }}>
          {title}
        </AppText>
        <AppText variant="caption" tone="muted">
          {detail}
        </AppText>
      </View>
    </View>
  );
}

/** One selectable plan. The whole row is the target, not a small radio dot. */
function PlanRow({
  plan,
  selected,
  highlight,
  disabled,
  onSelect,
}: {
  plan: PurchasePlan;
  selected: boolean;
  highlight: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  const { palette, spacing, radius } = useTheme();

  return (
    <Touchable
      onPress={onSelect}
      disabled={disabled}
      accessibilityRole="radio"
      accessibilityState={{ selected, disabled }}
      accessibilityLabel={`${plan.title}, ${plan.priceString}`}
      weight="card"
      baseOpacity={disabled ? 0.5 : 1}
      style={{
        borderRadius: radius.md,
        borderWidth: selected ? 2 : 1,
        borderColor: selected ? palette.accent : palette.border,
        backgroundColor: selected ? palette.accentContainer : palette.surface,
        padding: spacing.lg,
        marginBottom: spacing.sm,
        flexDirection: 'row',
        alignItems: 'center',
      }}
    >
      <View style={{ flex: 1 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 2 }}>
          <AppText
            variant="cardTitle"
            tone={selected ? 'onAccentContainer' : 'default'}
            style={{ fontSize: 14 }}
          >
            {plan.period === 'annual'
              ? 'Yearly'
              : plan.period === 'monthly'
                ? 'Monthly'
                : plan.period === 'lifetime'
                  ? 'Lifetime'
                  : plan.title}
          </AppText>
          {highlight ? (
            <View
              style={{
                marginLeft: spacing.sm,
                paddingHorizontal: spacing.sm,
                paddingVertical: 2,
                borderRadius: radius.pill,
                backgroundColor: palette.accent,
              }}
            >
              <AppText variant="label" tone="onAccent" style={{ fontSize: 10 }}>
                Best value
              </AppText>
            </View>
          ) : null}
        </View>
        {plan.secondary ? (
          <AppText variant="caption" tone={selected ? 'onAccentContainer' : 'muted'}>
            {plan.secondary}
          </AppText>
        ) : null}
      </View>

      {/* Store-formatted, rendered verbatim — never a number this app formats. */}
      <AppText
        variant="cardTitle"
        tone={selected ? 'onAccentContainer' : 'default'}
        style={{ fontSize: 15 }}
      >
        {plan.priceString}
      </AppText>
    </Touchable>
  );
}

/**
 * The one place a user can buy Weavr Pro.
 *
 * Two rules shape everything below, and both come from the server:
 *
 * 1. **The screen never claims Pro on the SDK's say-so.** `me.pro` comes from
 *    `GET /v1/me` through the local store, so what this screen shows and what
 *    the pipeline will actually allow cannot disagree — see `MeController`.
 * 2. **A purchase the server has not confirmed yet is its own state, not an
 *    error.** The store has taken the money; the webhook is in flight. Telling
 *    that user something failed would be false, and is the single worst thing
 *    this screen could say.
 */
export function PaywallScreen() {
  const { palette, spacing, radius, layout } = useTheme();
  const router = useRouter();
  const { trigger } = useLocalSearchParams<{ trigger?: string }>();

  useEffect(() => {
    track(AnalyticsEvent.ScreenViewed, { screen_name: 'paywall' });
    track(AnalyticsEvent.PaywallViewed, {
      trigger: trigger === 'entitlement_gate' ? 'entitlement_gate' : ('settings' as PaywallTrigger),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    available,
    unavailableReason,
    kind,
    plans,
    plansState,
    plansError,
    reloadPlans,
    busy,
    confirming,
    purchase,
    restore,
  } = usePurchases();

  const me = useLiveValue<MeResponse | null>(
    ['kv'],
    (store) => store.readKv<MeResponse>(KV.me),
    null,
  );

  // Annual first when the dashboard offers one — it is the plan most people
  // want and the one whose per-month line makes the comparison legible.
  const ordered = useMemo(() => {
    const rank: Record<PurchasePlan['period'], number> = {
      annual: 0,
      lifetime: 1,
      monthly: 2,
      other: 3,
    };
    return [...plans].sort((a, b) => rank[a.period] - rank[b.period]);
  }, [plans]);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = ordered.find((p) => p.id === selectedId) ?? ordered[0] ?? null;

  const [outcome, setOutcome] = useState<PurchaseOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<PurchaseOutcome>) => {
    setError(null);
    setOutcome(null);
    try {
      setOutcome(await action());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong. Please try again.');
    }
  };

  const alreadyPro = me?.pro === true;

  /**
   * After a `pending` outcome the user has been charged and the server has not
   * caught up. The provider's ~21s confirmation wait is over, so keep asking —
   * slower — for as long as this screen is open, and keep Subscribe locked: an
   * enabled button here invited a second purchase of the same thing.
   */
  const awaitingConfirmation = outcome?.kind === 'pending' && !alreadyPro;
  useEffect(() => {
    if (!awaitingConfirmation) return;
    const interval = setInterval(() => void sync.syncMe(), 10_000);
    return () => clearInterval(interval);
  }, [awaitingConfirmation]);
  const subscribeLocked = busy || !available || !selected || awaitingConfirmation;

  return (
    <Screen reserveNavSpace={false}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.xl,
        }}
      >
        <AppText variant="title">Weavr Pro</AppText>
        <Touchable
          onPress={() => router.back()}
          accessibilityRole="button"
          accessibilityLabel="Close"
          weight="control"
          style={{
            width: 34,
            height: 34,
            borderRadius: radius.pill,
            backgroundColor: palette.surface,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="close" size={16} color={palette.textMuted} />
        </Touchable>
      </View>

      {alreadyPro ? (
        <Card variant="accent" style={{ marginBottom: spacing.xl }}>
          <AppText variant="cardTitle" tone="onAccentContainer" style={{ marginBottom: spacing.xs }}>
            You're on Weavr Pro
          </AppText>
          <AppText variant="caption" tone="onAccentContainer">
            {me?.renewsAt
              ? `Renews ${new Date(me.renewsAt).toLocaleDateString()}. Manage or cancel it in your store account.`
              : 'Manage or cancel it in your store account.'}
          </AppText>
        </Card>
      ) : (
        <AppText variant="body" tone="muted" style={{ marginBottom: spacing.xl }}>
          Weavr runs every save through a shared, metered AI pipeline. Pro lifts the
          two caps that pays for.
        </AppText>
      )}

      <View style={{ marginBottom: spacing.md }}>
        {BENEFITS.map((benefit) => (
          <Benefit key={benefit.title} {...benefit} />
        ))}
      </View>

      {/* What the caps mean for this account right now, straight from the
          server's own counters — concrete beats a marketing sentence. */}
      {!alreadyPro && me && me.savesLimit > 0 ? (
        <Card style={{ marginBottom: spacing.xl }}>
          <AppText variant="caption" tone="muted">
            You've used {me.savesUsed} of {me.savesLimit} AI saves this month and{' '}
            {me.actsUsed} of {me.actsLimit} shopping lists this week.
          </AppText>
        </Card>
      ) : null}

      {!alreadyPro ? (
        <>
          {plansState === 'loading' ? (
            <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
              <ActivityIndicator color={palette.accent} />
            </View>
          ) : null}

          {plansState === 'ready'
            ? ordered.map((plan, index) => (
                <PlanRow
                  key={plan.id}
                  plan={plan}
                  selected={selected?.id === plan.id}
                  // Only ever the first row, and only when there is something
                  // to be better value *than*.
                  highlight={index === 0 && ordered.length > 1 && plan.period === 'annual'}
                  disabled={busy}
                  onSelect={() => setSelectedId(plan.id)}
                />
              ))
            : null}

          {plansState === 'empty' ? (
            <Card style={{ marginBottom: spacing.lg }}>
              <AppText variant="caption" tone="muted">
                No subscription is on sale yet. Pro will appear here as soon as it is.
              </AppText>
            </Card>
          ) : null}

          {plansState === 'unavailable' ? (
            <Card style={{ marginBottom: spacing.lg }}>
              <AppText variant="caption" tone="muted">
                {unavailableReason}
              </AppText>
            </Card>
          ) : null}

          {plansState === 'error' ? (
            <Card style={{ marginBottom: spacing.lg }}>
              <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.sm }}>
                {plansError}
              </AppText>
              <Touchable onPress={reloadPlans} weight="control">
                <AppText variant="label" tone="accent">
                  Try again
                </AppText>
              </Touchable>
            </Card>
          ) : null}
        </>
      ) : null}

      {/* ---- outcome, before the button so it is never scrolled past ---- */}

      {outcome?.kind === 'confirmed' ? (
        <Card variant="accent" style={{ marginBottom: spacing.lg }}>
          <AppText variant="caption" tone="onAccentContainer">
            You're on Weavr Pro. The caps are lifted.
          </AppText>
        </Card>
      ) : null}

      {outcome?.kind === 'pending' ? (
        <Card style={{ marginBottom: spacing.lg }}>
          <AppText variant="caption" tone="muted">
            Your purchase went through. Weavr is still confirming it with the store —
            this usually takes a few seconds, and your plan updates on its own. You
            have not been charged twice.
          </AppText>
        </Card>
      ) : null}

      {outcome?.kind === 'nothing' ? (
        <Card style={{ marginBottom: spacing.lg }}>
          <AppText variant="caption" tone="muted">
            No previous purchase was found on this store account.
          </AppText>
        </Card>
      ) : null}

      {error ? (
        <Card style={{ marginBottom: spacing.lg }}>
          <AppText variant="caption" tone="muted">
            {error}
          </AppText>
        </Card>
      ) : null}

      {!alreadyPro ? (
        <Touchable
          onPress={() => {
            if (!selected) return;
            track(AnalyticsEvent.PurchaseStarted, { package_id: selected.id });
            void run(() => purchase(selected.id));
          }}
          disabled={subscribeLocked}
          baseOpacity={subscribeLocked ? 0.5 : 1}
          accessibilityRole="button"
          accessibilityLabel="Subscribe to Weavr Pro"
          accessibilityState={{ disabled: subscribeLocked }}
          weight="card"
          haptic="medium"
          style={{
            backgroundColor: palette.accent,
            borderRadius: radius.md,
            paddingVertical: spacing.lg,
            alignItems: 'center',
            marginTop: spacing.sm,
            marginBottom: spacing.md,
          }}
        >
          {busy ? (
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <ActivityIndicator color={palette.onAccent} />
              <AppText variant="label" tone="onAccent" style={{ marginLeft: spacing.sm }}>
                {confirming ? 'Confirming your purchase…' : 'Opening the store…'}
              </AppText>
            </View>
          ) : (
            <AppText variant="label" tone="onAccent">
              {selected ? `Subscribe — ${selected.priceString}` : 'Subscribe'}
            </AppText>
          )}
        </Touchable>
      ) : null}

      <Touchable
        onPress={() => void run(restore)}
        disabled={busy || !available}
        baseOpacity={busy || !available ? 0.5 : 1}
        accessibilityRole="button"
        accessibilityLabel="Restore purchases"
        weight="control"
        style={{ alignItems: 'center', paddingVertical: spacing.md }}
      >
        <AppText variant="label" tone="accent">
          Restore purchases
        </AppText>
      </Touchable>

      {/* Conditional on the selected plan, not boilerplate: "renews
          automatically until cancelled" is simply false about a lifetime
          purchase, and a disclosure that is wrong about the thing being sold
          is worse than none. */}
      <AppText
        variant="caption"
        tone="faint"
        style={{ textAlign: 'center', marginTop: spacing.md, lineHeight: 17 }}
      >
        Billed through your{' '}
        {kind === 'mock' ? 'store account' : 'App Store or Google Play account'}.
        {selected?.period === 'lifetime'
          ? ' A one-off payment — nothing renews.'
          : ' Renews automatically until cancelled, which you can do any time in that account.'}
      </AppText>

      {hasLegalUrl(PRIVACY_POLICY_URL) ? (
        <Touchable
          onPress={() => void Linking.openURL(PRIVACY_POLICY_URL)}
          weight="control"
          style={{ alignItems: 'center', paddingVertical: spacing.md }}
        >
          <AppText variant="caption" tone="accent">
            Privacy policy
          </AppText>
        </Touchable>
      ) : null}

      {kind === 'mock' ? (
        <AppText
          variant="caption"
          tone="faint"
          style={{ textAlign: 'center', marginTop: spacing.sm }}
        >
          Mock mode — no store, no charge.
        </AppText>
      ) : null}

      <View style={{ height: layout.screenGutter }} />
    </Screen>
  );
}
