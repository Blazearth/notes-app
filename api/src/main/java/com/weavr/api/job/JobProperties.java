package com.weavr.api.job;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Job runner tuning.
 *
 * @param enabled       lets a deployment run API-only, and keeps the runner out
 *                      of tests that have no database
 * @param concurrency   how many jobs run at once. Capped at 4 on purpose:
 *                      ffmpeg, yt-dlp and OCR are CPU- and memory-hungry, and on
 *                      a free-tier instance parallel jobs OOM rather than queue
 * @param pollInterval  how long the poller sleeps when it finds no work
 * @param claimedBy     identifies this instance in {@code jobs.claimed_by}
 * @param retryBaseDelay first retry delay; grows exponentially per attempt
 * @param retryMaxDelay  ceiling for the exponential backoff
 * @param staleAfter    a job left {@code running} for longer than this is
 *                      assumed to belong to a process that died, and is
 *                      returned to the queue
 */
@Validated
@ConfigurationProperties(prefix = "weavr.jobs")
public record JobProperties(

        boolean enabled,

        @Min(1) @Max(4) int concurrency,

        Duration pollInterval,

        String claimedBy,

        Duration retryBaseDelay,

        Duration retryMaxDelay,

        Duration staleAfter
) {

    public JobProperties {
        if (pollInterval == null) pollInterval = Duration.ofSeconds(2);
        if (retryBaseDelay == null) retryBaseDelay = Duration.ofSeconds(30);
        if (retryMaxDelay == null) retryMaxDelay = Duration.ofHours(1);
        if (staleAfter == null) staleAfter = Duration.ofMinutes(15);
        if (claimedBy == null || claimedBy.isBlank()) claimedBy = defaultClaimedBy();
    }

    private static String defaultClaimedBy() {
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank() ? "local" : host) + ":" + ProcessHandle.current().pid();
    }
}
