package com.weavr.api.job;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes pipeline bookkeeping that has outlived its usefulness.
 *
 * <p>Nothing pruned {@code save_stages} or {@code jobs} before this existed, and
 * both grow forever. On a 500 MB Supabase free database that is a hard capacity
 * wall rather than untidiness — and {@code save_stages} reaches it fast, because
 * an uploaded screenshot is base64-encoded into its JSONB payload at roughly
 * 13 MB a row. Full reasoning in {@code docs/parallel-processing.md} §2.
 *
 * <h2>What makes a stage row safe to delete</h2>
 *
 * <p>{@code save_stages} is not purely diagnostic: {@link
 * com.weavr.api.save.ClassifySaveHandler} reads {@code stage='extracted'} for
 * its input and {@code stage='classified'} to skip re-spending a Gemini request
 * on a retry. Deleting either while the save could still be processed would
 * turn a retry into a second paid model call, or fail it outright.
 *
 * <p>So the rule is <b>terminal status first, age second</b>: only saves already
 * {@code ready} or {@code failed} are eligible. That deliberately excludes
 * {@code pending} — a save parked because the daily Gemini budget was spent is
 * waiting to be processed, not finished, and it can sit that way across several
 * days of exhausted budget. Age alone would delete exactly the input it is
 * waiting to be classified from.
 *
 * <h2>Why a fixed delay rather than a nightly cron</h2>
 *
 * <p>The free-tier instance restarts on every deploy, on OOM, and after a
 * spin-down; a cron scheduled for 03:00 simply does not run on a process that
 * was not alive at 03:00. A fixed delay from startup runs shortly after every
 * restart instead, which is the opposite failure mode and the harmless one —
 * the work is idempotent and bounded, so running it more often costs almost
 * nothing.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Safe to run in several instances at once, which matters here because a
 * local run and the Render deploy share one database. Deletes are idempotent
 * and each batch is its own transaction, so two sweepers racing means one of
 * them deletes fewer rows, never that anything is deleted twice or held under a
 * long lock.
 */
@Component
@ConditionalOnProperty(prefix = "weavr.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    /**
     * Stops a single sweep from running unbounded if deletion cannot keep up
     * with insertion. The next sweep picks up where this one stopped.
     */
    static final int MAX_BATCHES = 40;

    /**
     * Only a save that has genuinely finished. {@code pending} is excluded on
     * purpose — see the class comment.
     */
    private static final String TERMINAL_STATUSES = "('ready', 'failed')";

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    RetentionSweeper(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * ISO-8601 durations rather than a SpEL bean reference: a record registered
     * by {@code @ConfigurationPropertiesScan} is named
     * {@code weavr.retention-com.weavr.api.job.RetentionProperties}, not
     * {@code retentionProperties}, so {@code #{@retentionProperties…}} would
     * fail at startup rather than at compile time.
     */
    @Scheduled(
            initialDelayString = "${weavr.retention.initial-delay:PT5M}",
            fixedDelayString = "${weavr.retention.interval:PT6H}")
    public void sweep() {
        try {
            int stages = sweepStages();
            int succeeded = sweepJobs("succeeded", properties.succeededJobAfter());
            int failed = sweepJobs("failed", properties.failedJobAfter());

            if (stages + succeeded + failed > 0) {
                log.info("Retention sweep: {} stage row(s), {} succeeded job(s), {} failed job(s) deleted",
                        stages, succeeded, failed);
            } else {
                log.debug("Retention sweep: nothing to delete");
            }
        } catch (RuntimeException e) {
            // Retention is housekeeping. A failure here — a pooler hiccup, a
            // statement timeout — must never take down the instance serving
            // saves; the next sweep tries again.
            log.warn("Retention sweep failed, will retry next interval", e);
        }
    }

    /**
     * Stage rows belonging to saves that finished long enough ago that no retry
     * can still be in flight.
     *
     * <p>The longest legitimate retry window is bounded by {@code max_attempts}
     * and the backoff ceiling — roughly two hours — so the default of seven days
     * is not a close call.
     */
    int sweepStages() {
        return deleteInBatches("""
                delete from save_stages
                where ctid in (
                    select st.ctid
                    from save_stages st
                    join saves s on s.id = st.save_id
                    where s.status in %s
                      and s.updated_at < now() - (? * interval '1 second')
                    limit ?
                )
                """.formatted(TERMINAL_STATUSES), properties.stageAfter());
    }

    int sweepJobs(String status, Duration olderThan) {
        return deleteInBatches("""
                delete from jobs
                where ctid in (
                    select ctid
                    from jobs
                    where status = ?
                      and updated_at < now() - (? * interval '1 second')
                    limit ?
                )
                """, status, olderThan);
    }

    private int deleteInBatches(String sql, Duration olderThan) {
        return runBatches(sql, statement -> statement.param(olderThan.toSeconds()).param(properties.batchSize()));
    }

    private int deleteInBatches(String sql, String status, Duration olderThan) {
        return runBatches(sql, statement ->
                statement.param(status).param(olderThan.toSeconds()).param(properties.batchSize()));
    }

    /**
     * Deletes in bounded batches, each its own transaction.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}: one transaction around
     * the whole loop would hold a connection and a lock for the entire sweep,
     * which is exactly what a five-connection pool on a transaction pooler
     * cannot afford. Each statement autocommits, so a failure part-way through
     * keeps the batches that already succeeded.
     */
    private int runBatches(String sql, java.util.function.UnaryOperator<JdbcClient.StatementSpec> bind) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = bind.apply(jdbc.sql(sql)).update();
            total += deleted;
            if (deleted < properties.batchSize()) {
                return total;
            }
        }
        log.warn("Retention sweep hit its batch ceiling ({} rows) — deletion is not keeping up with insertion",
                MAX_BATCHES * properties.batchSize());
        return total;
    }
}
