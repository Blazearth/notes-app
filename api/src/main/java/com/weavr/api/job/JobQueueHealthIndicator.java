package com.weavr.api.job;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes the queue visible, because a {@code failed} job was previously terminal
 * <em>and</em> invisible: nothing surfaced it, no endpoint listed it, and the
 * only way to know a save had died was for the user to report it.
 *
 * <p>This is the dead-letter view from {@code docs/parallel-processing.md} §6,
 * expressed as a health contributor rather than a new admin endpoint — there is
 * no admin role in this codebase, and inventing an authorisation surface to
 * expose four integers would be a much larger change than the problem warrants.
 *
 * <h2>Why the counts are safe to expose</h2>
 *
 * <p>{@code /actuator/health} is public here. Every value below is a count or a
 * duration — no ids, no user ids, no payloads, no error text. That is deliberate:
 * {@code jobs.last_error} can contain a URL the user saved, and {@code group_id}
 * <em>is</em> a user id, so neither belongs on an unauthenticated endpoint.
 *
 * <h2>Why a backlog is not "down"</h2>
 *
 * <p>Reporting {@code DOWN} would make Render's health check restart the
 * instance, and restarting is precisely wrong for a queue that is merely busy —
 * it would abandon in-flight jobs to the stale reaper and rebuild the backlog it
 * was trying to clear. The status stays {@code UP} and the numbers carry the
 * signal; only being unable to read the queue at all is a real failure.
 */
// Bean name "jobs" rather than the class-derived default: it is the key this
// contributor appears under in /actuator/health, and "jobQueue" collides
// head-on with the JobQueue @Component — a ConflictingBeanDefinitionException
// at startup, which no unit test here boots a context to catch.
@Component("jobs")
public class JobQueueHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(JobQueueHealthIndicator.class);

    /**
     * A queued job older than this suggests the pipeline is stuck rather than
     * busy — long enough not to trip on an ordinary OCR job or a save parked by
     * {@link RetryAfterException} for a few minutes.
     */
    private static final Duration STALLED_AFTER = Duration.ofHours(1);

    private final JdbcClient jdbc;

    JobQueueHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Snapshot(int queued, int running, int failed24h, Integer oldestQueuedSeconds) {
        boolean worthReporting() {
            return queued > 0 || running > 0 || failed24h > 0;
        }
    }

    /**
     * Periodic queue summary in the application log.
     *
     * <p>The health contributor above only surfaces these numbers when
     * {@code management.endpoint.health.show-details} is enabled, and it is
     * not — that endpoint is unauthenticated here, and turning details on would
     * also expose the database and disk-space contributors' details along with
     * it. The log is where this project's operators actually look (every
     * incident in CLAUDE.md was diagnosed from Render's logs), so that is where
     * the numbers go.
     *
     * <p>Silent when there is nothing to say. A line every five minutes reading
     * "0 queued, 0 running" is how a log stops being read.
     */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${weavr.jobs.monitor-interval:PT5M}")
    public void logQueueDepth() {
        try {
            Snapshot s = snapshot();
            if (!s.worthReporting()) {
                return;
            }
            log.info("Job queue: {} queued, {} running, {} failed in 24h, oldest queued {}s",
                    s.queued(), s.running(), s.failed24h(),
                    s.oldestQueuedSeconds() == null ? 0 : s.oldestQueuedSeconds());

            if (s.oldestQueuedSeconds() != null && s.oldestQueuedSeconds() > STALLED_AFTER.toSeconds()) {
                log.warn("Job queue backlog is stalled — oldest runnable job has waited {}s. "
                                + "Check whether a lane's worker is wedged.",
                        s.oldestQueuedSeconds());
            }
        } catch (RuntimeException e) {
            log.debug("Queue depth log skipped: {}", e.toString());
        }
    }

    /**
     * One pass over {@code jobs}.
     *
     * <p>{@code FILTER} attaches to an <b>aggregate</b>, so it goes on
     * {@code min()} rather than on the {@code extract()} wrapping it. The other
     * way round is a syntax error Postgres raises only when the statement
     * actually runs — which is to say, never in a mocked test.
     */
    private Snapshot snapshot() {
        return jdbc.sql("""
                        select
                          count(*) filter (where status = 'queued')                          as queued,
                          count(*) filter (where status = 'running')                         as running,
                          count(*) filter (where status = 'failed'
                                           and updated_at > now() - interval '24 hours')     as failed_24h,
                          extract(epoch from (
                              now() - min(run_after) filter (
                                  where status = 'queued' and run_after <= now())
                          ))::int                                                            as oldest_queued
                        from jobs
                        """)
                .query((rs, row) -> {
                    int oldest = rs.getInt("oldest_queued");
                    return new Snapshot(
                            rs.getInt("queued"),
                            rs.getInt("running"),
                            rs.getInt("failed_24h"),
                            rs.wasNull() ? null : oldest);
                })
                .single();
    }

    @Override
    public Health health() {
        try {
            Snapshot s = snapshot();

            boolean stalled = s.oldestQueuedSeconds() != null
                    && s.oldestQueuedSeconds() > STALLED_AFTER.toSeconds();

            return Health.up()
                    .withDetail("queued", s.queued())
                    .withDetail("running", s.running())
                    .withDetail("failed24h", s.failed24h())
                    .withDetail("oldestQueuedSeconds",
                            s.oldestQueuedSeconds() == null ? 0 : s.oldestQueuedSeconds())
                    // The one derived signal worth naming, so an operator does
                    // not have to know what counts as too old.
                    .withDetail("backlogStalled", stalled)
                    .build();

        } catch (RuntimeException e) {
            // Being unable to read the queue is genuinely degraded — but report
            // it without the exception message, which can carry connection
            // strings on this public endpoint.
            log.warn("Job queue health check failed", e);
            return Health.down().withDetail("error", "queue unreadable").build();
        }
    }
}
