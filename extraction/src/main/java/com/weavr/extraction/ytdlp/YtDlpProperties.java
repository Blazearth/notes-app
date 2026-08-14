package com.weavr.extraction.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ported unchanged from {@code api}'s {@code YtDlpProperties}.
 *
 * @param binary         executable name or absolute path
 * @param probeTimeout   bound on the metadata probe
 * @param captionTimeout bound on the caption fetch
 * @param audioTimeout   bound on the audio download (for a needsTranscription artifact)
 * @param videoTimeout   bound on the OCR-tier video download
 * @param videoFormat    `-f` value for the video download
 * @param subtitleLangs  `--sub-langs` value — exact codes, never a regex
 * @param cookiesBase64  optional base64-encoded Netscape cookies file
 * @param playerClients  comma list for {@code --extractor-args youtube:player_client=...}
 */
@ConfigurationProperties(prefix = "weavr.ytdlp")
public record YtDlpProperties(
        String binary,
        Duration probeTimeout,
        Duration captionTimeout,
        Duration audioTimeout,
        Duration videoTimeout,
        String videoFormat,
        String subtitleLangs,
        String cookiesBase64,
        String playerClients
) {

    public YtDlpProperties {
        if (binary == null || binary.isBlank()) binary = "yt-dlp";
        if (probeTimeout == null) probeTimeout = Duration.ofSeconds(60);
        if (captionTimeout == null) captionTimeout = Duration.ofSeconds(90);
        if (audioTimeout == null) audioTimeout = Duration.ofSeconds(120);
        if (videoTimeout == null) videoTimeout = Duration.ofSeconds(180);
        if (videoFormat == null || videoFormat.isBlank()) videoFormat = "worst[height>=360]/worst";
        if (subtitleLangs == null || subtitleLangs.isBlank()) subtitleLangs = "en,en-orig";
        if (playerClients == null || playerClients.isBlank()) playerClients = "tv,android,web";
    }
}
