package com.weavr.api.analytics;

/**
 * Server-emitted event names — kept in sync with {@code app/src/analytics/events.ts}
 * by convention, not codegen (small team, ~20 events; see
 * {@code docs/weavr-analytics-plan.md} §O).
 *
 * <p>These are exactly the events the client-side taxonomy deliberately
 * excludes: capture/extraction/save reuses pipeline state that already
 * changes here, and the RevenueCat webhook is server truth by design.
 */
public final class AnalyticsEvents {

    public static final String CAPTURE_RECEIVED = "capture_received";
    public static final String CAPTURE_FAILED = "capture_failed";
    public static final String EXTRACTION_COMPLETED = "extraction_completed";
    public static final String EXTRACTION_FAILED = "extraction_failed";
    public static final String SAVE_READY = "save_ready";
    public static final String PURCHASE_CONFIRMED = "purchase_confirmed";

    private AnalyticsEvents() {
    }
}
