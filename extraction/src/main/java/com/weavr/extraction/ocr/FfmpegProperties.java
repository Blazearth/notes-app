package com.weavr.extraction.ocr;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ffmpeg is used here only to cut keyframes for OCR — the ASR downmix step
 * stays in the backend (docs/extraction-architecture.md Part D: "the Groq
 * Whisper call... stays in the backend, because they are AI calls with their
 * own budgets, keys and rate-limit accounting").
 *
 * @param binary  executable name or absolute path. The container MUST install it
 * @param timeout bound on one ffmpeg run
 */
@ConfigurationProperties(prefix = "weavr.ffmpeg")
public record FfmpegProperties(
        String binary,
        Duration timeout
) {

    public FfmpegProperties {
        if (binary == null || binary.isBlank()) binary = "ffmpeg";
        if (timeout == null) timeout = Duration.ofSeconds(60);
    }
}
