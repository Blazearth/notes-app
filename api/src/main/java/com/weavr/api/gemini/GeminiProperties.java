package com.weavr.api.gemini;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Gemini API configuration.
 *
 * <p>The budget lives in ai_budget_days (one row per day+model). The reset
 * timezone is US/Pacific — Google resets at midnight Pacific, not UTC. Use
 * {@code ZoneId.of("America/Los_Angeles")} everywhere a date is computed.
 */
@Validated
@ConfigurationProperties(prefix = "weavr.gemini")
public record GeminiProperties(

        /** The API key — injected from {@code WEAVR_GEMINI_API_KEY}. */
        String apiKey,

        /** Primary model identifier as accepted by the REST API. */
        String primaryModel,

        /** Fallback model — used when primary returns confidence < threshold. */
        String fallbackModel,

        /** Daily request ceiling for the primary model. */
        int primaryRpd,

        /** Daily request ceiling for the fallback model. */
        int fallbackRpd,

        /**
         * Confidence below this value triggers a retry with the fallback model.
         * Range 0.0–1.0.
         */
        double confidenceThreshold,

        /** Per-request HTTP timeout. */
        Duration timeout
) {
}
