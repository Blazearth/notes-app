package com.weavr.api.pipeline.health;

import java.time.Instant;

/**
 * One call to one extraction provider, recorded whether it worked or not.
 *
 * <p><b>Carries no URL, credential or response body by construction</b> —
 * only what's needed to answer "which platform, which provider, how often,
 * why, how slow". {@code detail} is a short stable code (e.g. a
 * {@code YtDlpErrors} error code or a Data API {@code reason}), never raw
 * stderr, which can echo cookies-file paths and request URLs.
 *
 * @param context      {@code "save"} for real saves, {@code "canary"} for health probes
 * @param httpStatus   null when the provider isn't HTTP or never answered
 * @param ytDlpVersion null for non-yt-dlp providers, or when the version couldn't be read
 */
public record ExtractionAttempt(
        String context,
        String platform,
        String provider,
        ExtractionFailureCategory category,
        long latencyMs,
        Integer httpStatus,
        String ytDlpVersion,
        String detail,
        Instant timestamp) {

    public boolean success() {
        return category == ExtractionFailureCategory.SUCCESS;
    }
}
