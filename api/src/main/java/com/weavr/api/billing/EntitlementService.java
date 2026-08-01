package com.weavr.api.billing;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is entitled to what, read server-side.
 *
 * <p>This is the whole point of the RevenueCat webhook: a client-side
 * entitlement check is trivially bypassed, and the resource being gated — a
 * request from a 500-a-day shared pool — is the expensive one. Nothing in the
 * pipeline may ask the client whether the user is Pro.
 *
 * <p>Reads are a single primary-key lookup and happen once per metered call, so
 * they are not cached. A cache here would introduce a window where a user who
 * has just paid still gets refused, which is the worst failure this system has.
 */
@Service
public class EntitlementService {

    /**
     * @param pro       whether the configured entitlement is currently active
     * @param renewsAt  when the current period ends; null for a lifetime
     *                  purchase or for a user who has never subscribed
     */
    public record Entitlement(boolean pro, String id, String status, Instant renewsAt) {

        static Entitlement free() {
            return new Entitlement(false, null, "inactive", null);
        }
    }

    private final JdbcClient jdbc;
    private final BillingProperties props;

    EntitlementService(JdbcClient jdbc, BillingProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Entitlement forUser(UUID userId) {
        return jdbc.sql("""
                        select entitlement, status, renews_at
                        from subscriptions
                        where user_id = ?
                        """)
                .param(userId)
                .query((rs, row) -> {
                    String entitlement = rs.getString("entitlement");
                    String status = rs.getString("status");
                    Instant renewsAt = rs.getTimestamp("renews_at") == null
                            ? null : rs.getTimestamp("renews_at").toInstant();
                    // Expiry is checked here as well as at write time. The
                    // webhook is the normal way a lapse is learned, but a
                    // delivery that never arrives must not leave someone
                    // entitled forever — the row carries its own expiry, so
                    // read it rather than trusting `status` alone.
                    boolean live = "active".equals(status)
                            && (renewsAt == null || renewsAt.isAfter(Instant.now()));
                    boolean pro = live && props.entitlementId().equals(entitlement);
                    return new Entitlement(pro, entitlement, status, renewsAt);
                })
                .optional()
                .orElseGet(Entitlement::free);
    }

    public boolean isPro(UUID userId) {
        return forUser(userId).pro();
    }
}
