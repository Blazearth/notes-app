package com.weavr.api.billing;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Turns a RevenueCat event into a row in {@code subscriptions}.
 *
 * <h2>Entitlement is derived from expiry, not from the event type</h2>
 * <p>The obvious implementation is a switch over RevenueCat's event types —
 * {@code INITIAL_PURCHASE} grants, {@code CANCELLATION} revokes, and so on. It
 * is wrong twice over. {@code CANCELLATION} means auto-renew was switched off,
 * <em>not</em> that access ended: the user has paid through to the end of the
 * period and cutting them off immediately is taking money for nothing. And the
 * type list grows — a type this code has never heard of would silently do
 * nothing.
 *
 * <p>So the only questions asked of an event are: does it carry our entitlement,
 * and has that entitlement expired? Every event type answers both, including the
 * ones that do not exist yet.
 *
 * <h2>Two guards, for two different failures</h2>
 * <ul>
 *   <li><b>Duplicate delivery</b> — at-least-once means the same event arrives
 *       twice. The insert into {@code billing_events} is keyed on RevenueCat's
 *       event id; a conflict means "already handled".</li>
 *   <li><b>Out-of-order delivery</b> — a retried RENEWAL can land after the
 *       EXPIRATION that followed it. {@code last_event_at} carries the newest
 *       event <em>timestamp</em> applied so far, and an older one is recorded
 *       but not applied. Without this, a delayed retry can hand a lapsed user a
 *       permanent subscription.</li>
 * </ul>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /**
     * Structural events that carry no entitlement state of their own.
     *
     * <p>{@code TRANSFER} moves a purchase between users and names its parties
     * in {@code transferred_from}/{@code transferred_to} rather than
     * {@code app_user_id}; {@code SUBSCRIBER_ALIAS} links two ids. Both are
     * followed by ordinary entitlement events for the affected users, so
     * treating them as no-ops loses nothing — and guessing at them would write
     * the wrong user's row.
     */
    private static final java.util.Set<String> STRUCTURAL_TYPES =
            java.util.Set.of("TRANSFER", "SUBSCRIBER_ALIAS", "TEST");

    /** Why an event did or did not change anything. Logged, and returned to RevenueCat. */
    public record Outcome(boolean applied, String reason) {
    }

    /**
     * What an event says, before any of it touches a database.
     *
     * <p>Static and database-free on purpose, the same way
     * {@code ShoppingListService.fold} is: this is where every rule that can be
     * got wrong lives — the expiry-versus-type reading, the lifetime-purchase
     * null, the anonymous-id case — and a test that has to mock a
     * {@code JdbcClient} to reach them tests the mock instead.
     *
     * @param skipReason non-null when the event carries nothing to apply, in
     *                   which case every other field is unset
     */
    public record Decision(UUID userId, String entitlement, boolean active,
                           Instant expiresAt, Instant eventAt, String skipReason) {

        static Decision skip(String reason) {
            return new Decision(null, null, false, null, null, reason);
        }

        public boolean shouldApply() {
            return skipReason == null;
        }
    }

    /**
     * Reads an event. See the class javadoc for why entitlement is derived from
     * expiry rather than from the event type.
     */
    public static Decision decide(JsonNode event, String entitlementId) {
        String type = event.path("type").asString("");
        if (STRUCTURAL_TYPES.contains(type)) {
            return Decision.skip("structural_event");
        }

        UUID userId = resolveUser(event);
        if (userId == null) {
            // An anonymous RevenueCat id: a purchase made before the user
            // signed in. Nothing to attribute it to, and no retry will change
            // that — the SDK sends the real id on the next event after logIn().
            return Decision.skip("unknown_user");
        }

        Instant expiresAt = millisToInstant(event.path("expiration_at_ms"));
        String entitlement = entitlementOf(event, entitlementId);
        // A null expiry is a lifetime or non-renewing purchase, not "expired
        // long ago" — the distinction is the difference between honouring a
        // one-off purchase and refusing it.
        boolean active = entitlementId.equals(entitlement)
                && (expiresAt == null || expiresAt.isAfter(Instant.now()));

        return new Decision(userId, entitlement, active, expiresAt,
                millisToInstant(event.path("event_timestamp_ms")), null);
    }

    private final JdbcClient jdbc;
    private final BillingProperties props;

    BillingService(JdbcClient jdbc, BillingProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional
    public Outcome apply(JsonNode event, String rawBody) {
        String eventId = event.path("id").asString("");
        Decision decision = decide(event, props.entitlementId());

        // Dedupe first, and in the same transaction as the write below: two
        // concurrent deliveries of the same event both reach here, and exactly
        // one wins the insert.
        boolean firstDelivery = jdbc.sql("""
                        insert into billing_events (id, user_id, type, payload)
                        values (?, ?, ?, ?::jsonb)
                        on conflict (id) do nothing
                        """)
                .param(eventId)
                .param(decision.userId())
                .param(event.path("type").asString(""))
                .param(rawBody)
                .update() > 0;

        if (!firstDelivery) {
            return new Outcome(false, "duplicate");
        }
        if (!decision.shouldApply()) {
            return new Outcome(false, decision.skipReason());
        }
        if (!profileExists(decision.userId())) {
            log.warn("RevenueCat event {} names user {}, which has no profile row",
                    eventId, decision.userId());
            return new Outcome(false, "no_profile");
        }

        if (upsertSubscription(event, decision, eventId) == 0) {
            // The guard in the WHERE clause rejected it: something newer is
            // already applied.
            return new Outcome(false, "out_of_order");
        }

        jdbc.sql("update billing_events set applied = true where id = ?").param(eventId).update();
        log.info("Subscription for {} → {} (entitlement={}, expires={})",
                decision.userId(), decision.active() ? "active" : "inactive",
                decision.entitlement(), decision.expiresAt());
        return new Outcome(true, decision.active() ? "active" : "inactive");
    }

    /**
     * The {@code last_event_at} guard lives in the WHERE clause rather than in a
     * read-then-write, so two deliveries racing each other cannot both pass the
     * check before either writes.
     */
    private int upsertSubscription(JsonNode event, Decision decision, String eventId) {
        return jdbc.sql("""
                        insert into subscriptions (user_id, revenuecat_customer_id, entitlement, status,
                                                   renews_at, product_id, store, environment,
                                                   last_event_at, last_event_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (user_id) do update
                            set revenuecat_customer_id = excluded.revenuecat_customer_id,
                                entitlement            = excluded.entitlement,
                                status                 = excluded.status,
                                renews_at              = excluded.renews_at,
                                product_id             = excluded.product_id,
                                store                  = excluded.store,
                                environment            = excluded.environment,
                                last_event_at          = excluded.last_event_at,
                                last_event_id          = excluded.last_event_id
                        where subscriptions.last_event_at is null
                           or subscriptions.last_event_at <= excluded.last_event_at
                        """)
                .param(decision.userId())
                .param(event.path("app_user_id").asString(null))
                .param(decision.entitlement())
                .param(decision.active() ? "active" : "inactive")
                .param(timestamp(decision.expiresAt()))
                .param(event.path("product_id").asString(null))
                .param(event.path("store").asString(null))
                .param(event.path("environment").asString(null))
                .param(timestamp(decision.eventAt()))
                .param(eventId)
                .update();
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    /**
     * {@code entitlement_ids} is the current field and {@code entitlement_id}
     * the older singular one; events in the wild still carry either.
     */
    private static String entitlementOf(JsonNode event, String entitlementId) {
        JsonNode ids = event.path("entitlement_ids");
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                // Ours wins if present — an event can grant several, and the
                // only one this app gates on is the configured one.
                if (entitlementId.equals(id.asString(""))) {
                    return entitlementId;
                }
            }
            if (!ids.isEmpty()) {
                return ids.get(0).asString(null);
            }
        }
        return event.path("entitlement_id").asString(null);
    }

    /**
     * The app calls {@code Purchases.logIn(supabaseUserId)}, so
     * {@code app_user_id} is our UUID. Before that call it is RevenueCat's own
     * anonymous id ({@code $RCAnonymousID:…}), which parses as nothing.
     */
    private static UUID resolveUser(JsonNode event) {
        UUID fromAppUserId = parseUuid(event.path("app_user_id").asString(null));
        return fromAppUserId != null
                ? fromAppUserId
                : parseUuid(event.path("original_app_user_id").asString(null));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant millisToInstant(JsonNode node) {
        return node.isNumber() ? Instant.ofEpochMilli(node.asLong()) : null;
    }

    private boolean profileExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.sql("select true from profiles where id = ?")
                .param(userId)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }
}
