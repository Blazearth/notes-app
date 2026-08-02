package com.weavr.api.pipeline.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param binary         executable name or absolute path
 * @param probeTimeout   bound on the metadata probe
 * @param captionTimeout bound on the caption fetch
 * @param audioTimeout   bound on the ASR audio download
 * @param videoTimeout   bound on the OCR-tier video download
 * @param videoFormat    `-f` value for the video download
 * @param subtitleLangs  `--sub-langs` value — exact codes, never a regex
 * @param cookiesBase64  optional base64-encoded Netscape cookies file
 *                       (set {@code WEAVR_YTDLP_COOKIES_BASE64} in the
 *                       environment). When present, decoded to a temp file
 *                       at startup and passed as {@code --cookies} to every
 *                       yt-dlp call — required on cloud IPs where YouTube
 *                       returns "Sign in to confirm you're not a bot"
 * @param playerClients  comma list for {@code --extractor-args
 *                       youtube:player_client=...}. Cookies alone did not
 *                       clear the bot check on Render's datacenter IP (a
 *                       cookie exported on a residential machine and replayed
 *                       from an unrelated cloud IP is itself a signal); the
 *                       {@code web} client's challenge needs a PO token that
 *                       cookies don't supply. {@code tv} and {@code android}
 *                       use a simpler auth flow that doesn't ask for one.
 *                       Empirical, not guaranteed — measure on the real
 *                       deploy rather than assuming this clears it.
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
        // cookiesBase64 is optional — null means no --cookies flag
        if (playerClients == null || playerClients.isBlank()) playerClients = "tv,android,web";
    }
}
