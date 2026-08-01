package com.weavr.api.billing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.weavr.api.billing.BillingService.Decision;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide whether someone has paid.
 *
 * <p>{@link BillingService#decide} is static and takes a parsed event, so every
 * case here is reachable without a database — deliberate, and the same shape as
 * {@code ShoppingListFoldTest}. What is <em>not</em> reachable this way is the
 * pair of guards that live in SQL: the {@code on conflict (id) do nothing}
 * dedupe and the {@code last_event_at <= excluded.last_event_at} ordering
 * guard. Those are database behaviour, they are pinned by comments in
 * {@code V6__billing.sql}, and they are unverified until something runs against
 * a real Postgres.
 */
class BillingDecisionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PRO = "pro";
    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static JsonNode event(String json) {
        return MAPPER.readTree(json);
    }

    private static String iso(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    @Test
    void grantsProWhenTheEntitlementIsPresentAndUnexpired() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_1",
                  "type": "INITIAL_PURCHASE",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(30, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.shouldApply()).isTrue();
        assertThat(decision.userId()).isEqualTo(USER);
        assertThat(decision.entitlement()).isEqualTo(PRO);
        assertThat(decision.active()).isTrue();
    }

    /**
     * The one that a type-switch implementation gets wrong. CANCELLATION means
     * auto-renew was switched off, not that access ended — the user has paid
     * through to the end of the period, and cutting them off the moment they
     * cancel is taking money for nothing.
     */
    @Test
    void cancellationBeforeExpiryKeepsAccessUntilTheEndOfThePeriod() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_2",
                  "type": "CANCELLATION",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(12, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.active())
                .as("cancelled but paid through — still entitled")
                .isTrue();
    }

    @Test
    void expirationInThePastRevokes() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_3",
                  "type": "EXPIRATION",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().minus(1, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.shouldApply()).isTrue();
        assertThat(decision.active()).isFalse();
    }

    /**
     * A non-renewing or lifetime purchase carries no expiry. Reading a missing
     * field as epoch-zero would revoke it instantly — the failure would look
     * like "my lifetime purchase does nothing", which is the worst possible bug
     * to have in billing.
     */
    @Test
    void missingExpiryIsALifetimePurchaseNotAnExpiredOne() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_4",
                  "type": "NON_RENEWING_PURCHASE",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"]
                }
                """.formatted(USER)), PRO);

        assertThat(decision.expiresAt()).isNull();
        assertThat(decision.active()).isTrue();
    }

    /** An event type this code has never seen still resolves, because nothing switches on the type. */
    @Test
    void anUnknownEventTypeStillResolvesFromItsExpiry() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_5",
                  "type": "SOME_TYPE_INVENTED_IN_2027",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(1, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.shouldApply()).isTrue();
        assertThat(decision.active()).isTrue();
    }

    @Test
    void someoneElsesEntitlementDoesNotMakeThemPro() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_6",
                  "type": "INITIAL_PURCHASE",
                  "app_user_id": "%s",
                  "entitlement_ids": ["some_other_tier"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(30, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.shouldApply()).isTrue();
        assertThat(decision.entitlement()).isEqualTo("some_other_tier");
        assertThat(decision.active()).isFalse();
    }

    @Test
    void picksOursOutOfSeveralGrantedEntitlements() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_7",
                  "type": "RENEWAL",
                  "app_user_id": "%s",
                  "entitlement_ids": ["legacy_tier", "pro", "beta"],
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(30, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.entitlement()).isEqualTo(PRO);
        assertThat(decision.active()).isTrue();
    }

    /** Older events still carry the singular field. */
    @Test
    void readsTheLegacySingularEntitlementField() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_8",
                  "type": "RENEWAL",
                  "app_user_id": "%s",
                  "entitlement_id": "pro",
                  "expiration_at_ms": %s
                }
                """.formatted(USER, iso(Instant.now().plus(30, ChronoUnit.DAYS)))), PRO);

        assertThat(decision.entitlement()).isEqualTo(PRO);
        assertThat(decision.active()).isTrue();
    }

    /**
     * A purchase made before sign-in. Nothing to attribute it to, and no retry
     * will change that — so it must be a skip, not an error, or RevenueCat
     * redelivers it forever.
     */
    @Test
    void anAnonymousRevenueCatIdIsSkippedRatherThanFailed() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_9",
                  "type": "INITIAL_PURCHASE",
                  "app_user_id": "$RCAnonymousID:8a3f9c2b1e",
                  "entitlement_ids": ["pro"]
                }
                """), PRO);

        assertThat(decision.shouldApply()).isFalse();
        assertThat(decision.skipReason()).isEqualTo("unknown_user");
    }

    @Test
    void fallsBackToTheOriginalAppUserIdWhenTheCurrentOneIsAnonymous() {
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_10",
                  "type": "RENEWAL",
                  "app_user_id": "$RCAnonymousID:8a3f9c2b1e",
                  "original_app_user_id": "%s",
                  "entitlement_ids": ["pro"]
                }
                """.formatted(USER)), PRO);

        assertThat(decision.userId()).isEqualTo(USER);
    }

    /**
     * TRANSFER names its parties in {@code transferred_from}/{@code _to} rather
     * than {@code app_user_id}. Guessing at it would write the wrong user's
     * row; the ordinary entitlement events that follow it do the real work.
     */
    @Test
    void structuralEventsAreSkipped() {
        assertThat(BillingService.decide(event("""
                {"id": "evt_11", "type": "TRANSFER", "transferred_to": ["%s"]}
                """.formatted(USER)), PRO).skipReason())
                .isEqualTo("structural_event");

        assertThat(BillingService.decide(event("""
                {"id": "evt_12", "type": "TEST", "app_user_id": "%s"}
                """.formatted(USER)), PRO).skipReason())
                .isEqualTo("structural_event");
    }

    @Test
    void carriesTheEventsOwnTimestampForTheOrderingGuard() {
        Instant sent = Instant.now().minus(3, ChronoUnit.HOURS);
        Decision decision = BillingService.decide(event("""
                {
                  "id": "evt_13",
                  "type": "RENEWAL",
                  "app_user_id": "%s",
                  "entitlement_ids": ["pro"],
                  "event_timestamp_ms": %s
                }
                """.formatted(USER, iso(sent))), PRO);

        assertThat(decision.eventAt()).isEqualTo(sent.truncatedTo(ChronoUnit.MILLIS));
    }
}
