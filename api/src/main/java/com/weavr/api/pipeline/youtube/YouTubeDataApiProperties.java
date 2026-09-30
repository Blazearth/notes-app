package com.weavr.api.pipeline.youtube;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The official YouTube Data API v3 — the legitimate metadata fallback when
 * RapidAPI fails. Disabled (never called) when {@code apiKey} is blank.
 *
 * @param apiKey  {@code WEAVR_YOUTUBE_API_KEY}, a Google Cloud key with the
 *                YouTube Data API v3 enabled. Server-side only — never sent to the app.
 * @param timeout per-request read timeout
 */
@ConfigurationProperties(prefix = "weavr.youtube-data-api")
public record YouTubeDataApiProperties(String apiKey, Duration timeout) {

    public YouTubeDataApiProperties {
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
