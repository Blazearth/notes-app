package com.weavr.api.analytics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Fire-and-forget events to PostHog's server capture endpoint.
 *
 * <p><b>An analytics outage must never fail a save, a webhook, or a request
 * thread.</b> This mirrors {@code GeminiClient.logCall}'s idiom exactly: try
 * the network call, catch everything, log a warning, never rethrow —
 * CLAUDE.md's async pattern for this codebase is "try/catch + log.warn," not
 * a dedicated executor (there is no {@code @Async} anywhere in {@code api/}),
 * so this stays consistent with every other "must not break the caller"
 * side-effect here.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /** PostHog's server-side capture endpoint. */
    private static final String CAPTURE_PATH = "/i/v0/e/";

    /** Used when no user can be attributed — an event should still land, just unattributed. */
    private static final String UNKNOWN_USER = "unknown_user";

    private final RestClient http;
    private final AnalyticsProperties props;
    private final ObjectMapper objectMapper;

    AnalyticsService(AnalyticsProperties props, ObjectMapper objectMapper,
                     @Qualifier("posthog") RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * @param distinctId the Supabase user id — the same {@code distinct_id} the
     *                    client uses (§F), so one PostHog person merges both
     *                    sides with no alias logic. Null falls back to
     *                    {@link #UNKNOWN_USER} rather than skipping the event.
     */
    public void capture(UUID distinctId, String event, Map<String, Object> properties) {
        if (!props.enabled() || props.apiKey() == null || props.apiKey().isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", props.apiKey());
            body.put("event", event);
            body.put("distinct_id", distinctId != null ? distinctId.toString() : UNKNOWN_USER);
            body.put("timestamp", Instant.now().toString());
            body.put("properties", properties == null ? Map.of() : properties);

            http.post()
                    .uri(props.host() + CAPTURE_PATH)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to capture analytics event {}: {}", event, e.toString());
        }
    }
}
