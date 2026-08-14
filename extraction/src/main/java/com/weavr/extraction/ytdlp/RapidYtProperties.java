package com.weavr.extraction.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RapidAPI "ytstream-download-youtube-videos" endpoint used as the primary
 * metadata + caption path for YouTube URLs — bypasses the datacenter-IP bot
 * check that a plain yt-dlp probe hits. Ported unchanged from {@code api}.
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
