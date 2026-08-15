package com.weavr.api.job;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retention for the pipeline's own bookkeeping tables.
 *
 * <p>Neither {@code save_stages} nor {@code jobs} was ever pruned, and both grow
 * without bound against a 500 MB free-tier database. {@code save_stages} is the
 * urgent one: {@code ProcessSaveHandler} base64-encodes an uploaded screenshot —
 * up to 10 MB — into its JSONB payload, so roughly 45 image saves fill the
 * database on their own. See {@code docs/parallel-processing.md} §2.
 *
 * @param enabled            lets a deployment opt out entirely
 * @param stageAfter         how long a *terminal* save keeps its stage
 *                           breadcrumbs. Only ever applied to saves that are
 *                           already {@code ready} or {@code failed} — a save
 *                           still processing, or parked waiting for tomorrow's
 *                           Gemini budget, keeps everything regardless of age
 * @param succeededJobAfter  how long a succeeded job row is kept. Nothing reads
 *                           these; they are audit trail
 * @param failedJobAfter     how long a failed job row is kept. Longer than a
 *                           succeeded one on purpose: a failure is the only
 *                           record of *why* a save did not work, and it is the
 *                           thing an operator goes looking for
 * @param batchSize          rows deleted per statement. Bounded so the sweep
 *                           never holds a long transaction open on the
 *                           transaction pooler
 * @param initialDelay       delay after startup before the first sweep
 * @param interval           gap between sweeps. Deliberately a fixed delay
 *                           rather than a daily cron: a free-tier instance
 *                           restarts on deploy, on OOM and after a spin-down,
 *                           and a cron scheduled for a moment the process was
 *                           not alive is simply missed
 */
@Validated
@ConfigurationProperties(prefix = "weavr.retention")
public record RetentionProperties(

        boolean enabled,

        Duration stageAfter,

        Duration succeededJobAfter,

        Duration failedJobAfter,

        @Min(1) int batchSize,

        Duration initialDelay,

        Duration interval
) {

    public RetentionProperties {
        if (stageAfter == null) stageAfter = Duration.ofDays(7);
        if (succeededJobAfter == null) succeededJobAfter = Duration.ofDays(7);
        if (failedJobAfter == null) failedJobAfter = Duration.ofDays(30);
        if (batchSize < 1) batchSize = 500;
        if (initialDelay == null) initialDelay = Duration.ofMinutes(5);
        if (interval == null) interval = Duration.ofHours(6);
    }
}
