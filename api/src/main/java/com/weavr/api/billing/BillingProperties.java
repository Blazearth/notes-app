package com.weavr.api.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RevenueCat and free-tier configuration.
 *
 * <p>{@code webhookSecret} is the value RevenueCat sends in its
 * {@code Authorization} header, set on the webhook in their dashboard. It is
 * the only thing standing between the open internet and a table that decides
 * who has paid, so an unset secret disables the endpoint rather than opening
 * it — see {@link RevenueCatWebhookController}.
 */
@Validated
@ConfigurationProperties(prefix = "weavr.billing")
public record BillingProperties(

        /** Shared secret RevenueCat sends as its {@code Authorization} header. */
        String webhookSecret,

        /**
         * The entitlement identifier configured in RevenueCat, e.g. {@code pro}.
         * An event that grants some other entitlement is recorded but does not
         * make the user Pro here.
         */
        String entitlementId,

        /** Free-tier AI saves per calendar month. */
        int freeSavesPerMonth,

        /** Free-tier Act conversions per ISO week. */
        int freeActsPerWeek,

        /**
         * Whether to enforce the caps above, as opposed to only counting.
         *
         * <p>Off by default, and that is a product decision rather than a
         * half-finished one: a cap is only meaningful once there is a paid tier
         * to escape to, and until the store records exist nobody can subscribe.
         * Enforcing before then would cap every user with no way out. The
         * counters run regardless, so the switch can be flipped against real
         * numbers instead of an empty table.
         */
        boolean enforceFreeCaps
) {
}
