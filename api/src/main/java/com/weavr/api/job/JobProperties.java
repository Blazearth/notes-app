package com.weavr.api.job;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Job runner tuning.
 *
 * @param enabled       lets a deployment run API-only, and keeps the runner out
 *                      of tests that have no database
 * @param concurrency   legacy single-pool size. Used only when {@code lanes} is
 *                      empty, which reproduces the pre-lane behaviour exactly —
 *                      one pool serving every job type
 * @param lanes         the worker lanes, keyed by name. See
 *                      {@link JobLaneProperties}: a lane is a cost class, and
 *                      splitting them is what stops a nine-minute OCR job from
 *                      blocking a three-second Gemini call
 * @param pollInterval  how long a lane's poller sleeps when it finds no work
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

        @Min(1) @Max(8) int concurrency,

        Map<String, JobLaneProperties> lanes,

        Duration pollInterval,

        String claimedBy,

        Duration retryBaseDelay,

        Duration retryMaxDelay,

        Duration staleAfter
) {

    /** Name of the lane synthesised when nothing is configured. */
    static final String DEFAULT_LANE = "default";

    public JobProperties {
        if (pollInterval == null) pollInterval = Duration.ofSeconds(2);
        if (retryBaseDelay == null) retryBaseDelay = Duration.ofSeconds(30);
        if (retryMaxDelay == null) retryMaxDelay = Duration.ofHours(1);
        if (staleAfter == null) staleAfter = Duration.ofMinutes(15);
        if (claimedBy == null || claimedBy.isBlank()) claimedBy = defaultClaimedBy();
        lanes = lanes == null ? Map.of() : Map.copyOf(lanes);
    }

    /**
     * The lanes to actually run.
     *
     * <p>An unconfigured deployment gets one fallback lane at {@code
     * concurrency}, which is byte-for-byte the old behaviour — worth preserving
     * because {@code render.yaml} has been setting {@code
     * WEAVR_JOBS_CONCURRENCY} since before lanes existed, and a config file that
     * silently stopped taking effect would be a bad way to find that out.
     */
    public Map<String, JobLaneProperties> resolvedLanes() {
        if (lanes.isEmpty()) {
            return Map.of(DEFAULT_LANE, new JobLaneProperties(concurrency, List.of(), true));
        }
        return new LinkedHashMap<>(lanes);
    }

    private static String defaultClaimedBy() {
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank() ? "local" : host) + ":" + ProcessHandle.current().pid();
    }
}
