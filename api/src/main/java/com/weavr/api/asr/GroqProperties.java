package com.weavr.api.asr;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Groq Whisper configuration — ASR, step 3 of the extraction cascade.
 *
 * <p>A different provider from Gemini on purpose: it has its own key, its own
 * failure modes, and — the part that matters for the request budget — its own
 * rate-limit pool, so transcribing audio never spends any of Gemini's daily
 * RPD (CLAUDE.md § the request budget).
 *
 * @param apiKey            injected from {@code WEAVR_GROQ_API_KEY}
 * @param model             Whisper model id, e.g. {@code whisper-large-v3-turbo}
 * @param timeout           per-request HTTP timeout
 * @param maxAudioSeconds   upper bound on how much audio yt-dlp downloads per
 *                          save — long-form video would otherwise stall a
 *                          worker (CLAUDE.md § bound everything)
 */
@ConfigurationProperties(prefix = "weavr.groq")
public record GroqProperties(
        String apiKey,
        String model,
        Duration timeout,
        int maxAudioSeconds
) {

    public GroqProperties {
        if (model == null || model.isBlank()) model = "whisper-large-v3-turbo";
        if (timeout == null) timeout = Duration.ofSeconds(60);
        if (maxAudioSeconds <= 0) maxAudioSeconds = 90;
    }
}
