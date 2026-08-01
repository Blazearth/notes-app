package com.weavr.api.profile;

import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.billing.EntitlementService;
import com.weavr.api.billing.UsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the account screen needs, in one request.
 *
 * <p>Entitlement is reported by the server rather than read from the RevenueCat
 * SDK on the device, for the same reason it is enforced here: the two can
 * disagree — a webhook that has not landed yet, a receipt validated on another
 * device — and when they do, the server's answer is the one that governs what
 * actually happens. A settings screen that says "Pro" while every save is
 * refused is worse than one that lags by a few seconds.
 */
@RestController
class MeController {

    /**
     * @param savesLimit {@code -1} for unlimited — either the user is Pro or
     *                   caps are not being enforced yet. The client renders a
     *                   usage meter only when this is positive.
     */
    record MeResponse(UUID userId, boolean pro, String entitlement, String subscriptionStatus,
                      java.time.Instant renewsAt,
                      int savesUsed, int savesLimit, int actsUsed, int actsLimit) {
    }

    private final EntitlementService entitlements;
    private final UsageService usage;
    private final ProfileService profiles;

    MeController(EntitlementService entitlements, UsageService usage, ProfileService profiles) {
        this.entitlements = entitlements;
        this.usage = usage;
        this.profiles = profiles;
    }

    @GetMapping("/v1/me")
    MeResponse me(@CurrentUser UUID userId) {
        // A user can reach this screen before ever creating a save, which is
        // otherwise the only thing that mints their profile row.
        profiles.ensureExists(userId);

        EntitlementService.Entitlement entitlement = entitlements.forUser(userId);
        UsageService.UsageSummary summary = usage.summary(userId);
        return new MeResponse(
                userId,
                entitlement.pro(),
                entitlement.id(),
                entitlement.status(),
                entitlement.renewsAt(),
                summary.savesUsed(),
                summary.savesLimit(),
                summary.actsUsed(),
                summary.actsLimit());
    }
}
