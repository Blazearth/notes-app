package com.weavr.api.pipeline.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RapidAPI "ytstream-download-youtube-videos" endpoint used as the primary
 * metadata + caption path for YouTube URLs.
 *
 * <p>When {@code apiKey} is blank the client is disabled and the cascade falls
 * back to yt-dlp immediately, so the property is optional in dev.
 *
 * @param apiKey  {@code WEAVR_RAPID_YT_API_KEY} — RapidAPI key
 * @param timeout per-request timeout
 */
@ConfigurationProperties(prefix = "weavr.rapid-yt")
public record RapidYtProperties(
        String apiKey,
        Duration timeout
) {
    private static final String HOST = "ytstream-download-youtube-videos.p.rapidapi.com";
    static final String BASE_URL = "https://" + HOST;
    static final String RAPID_HOST = HOST;

    public RapidYtProperties {
        if (timeout == null) timeout = Duration.ofSeconds(15);
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
