package com.weavr.api.analytics;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostHog server-capture configuration — see {@code docs/weavr-analytics-plan.md}.
 *
 * <p>Most captures never route through the app's JS layer at all (Android's
 * silent share-intent receiver runs entirely outside the RN SDK), so the
 * capture/extraction/save events are emitted from here rather than the
 * client — see {@link AnalyticsEvents}.
 *
 * @param enabled false locally, true only on Render — {@code weavr.analytics.enabled}
 * @param apiKey  PostHog project API key, injected from {@code WEAVR_POSTHOG_API_KEY}.
 *                Blank disables capture rather than breaking the pipeline.
 * @param host    PostHog ingestion host — defaults to PostHog Cloud US.
 * @param timeout per-request HTTP timeout
 */
@ConfigurationProperties(prefix = "weavr.analytics")
public record AnalyticsProperties(
        boolean enabled,
        String apiKey,
        String host,
        Duration timeout
) {

    public AnalyticsProperties {
        if (host == null || host.isBlank()) host = "https://us.i.posthog.com";
        if (timeout == null) timeout = Duration.ofSeconds(5);
    }
}
