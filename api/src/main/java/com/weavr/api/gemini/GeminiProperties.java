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
         * Per-minute request ceiling for the primary model.
         *
         * <p>Separate from the daily one and enforced separately (see
         * {@link ModelRateLimiter}). Daily protects the quota; this protects
         * against 429s, which cost a retry attempt rather than merely a wait.
         */
        int primaryRpm,

        /** Per-minute request ceiling for the fallback model. */
        int fallbackRpm,

        /**
         * How long a caller will wait for a rate-limit slot before the job is
         * handed back to the queue instead. Kept short: holding one of a small
         * number of worker slots on a sleep is worse than requeueing, which
         * costs no attempt.
         */
        Duration rateLimitMaxWait,

        /**
         * Confidence below this value triggers a retry with the fallback model.
         * Range 0.0–1.0.
         */
        double confidenceThreshold,

        /** Per-request HTTP timeout. */
        Duration timeout
) {

    public GeminiProperties {
        // Verified against this project's own AI Studio dashboard on
        // 2026-08-05 (CLAUDE.md): 15 RPM primary, 5 RPM fallback. Defaulted
        // here so a config file predating the limiter still gets protection
        // rather than an unbounded rate.
        if (primaryRpm <= 0) primaryRpm = 15;
        if (fallbackRpm <= 0) fallbackRpm = 5;
        if (rateLimitMaxWait == null) rateLimitMaxWait = Duration.ofSeconds(20);
    }
}
