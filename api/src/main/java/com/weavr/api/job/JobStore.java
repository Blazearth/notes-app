package com.weavr.api.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Claim side of the Postgres-backed queue. The enqueue side is {@link JobQueue}.
 */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final JobProperties properties;

    JobStore(JdbcClient jdbc, ObjectMapper objectMapper, JobProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Claims one job of the given types, or returns empty if there is nothing
     * runnable in that lane.
     *
     * <p>Four things are load-bearing in this query:
     *
     * <p><b>{@code FOR UPDATE SKIP LOCKED}</b> in the subquery is what lets two
     * workers claim concurrently without blocking on each other — the second one
     * steps over the locked row instead of waiting for it.
     *
     * <p><b>The {@code group_id} exclusion</b> is the anti-monopoly rule: a group
     * that already has a job running will not get a second worker. This matters
     * <em>more</em> now than it did at concurrency 1, where nothing could run in
     * parallel anyway — with a fast lane several slots wide it is what stops one
     * user's backlog from occupying all of them while another user's single save
     * waits. Note it is global rather than per-lane on purpose: a user should not
     * get a heavy slot and a fast slot at once.
     *
     * <p><b>The type filter</b> is the lane split. Without it every poller
     * competes for every job and the lanes are decorative.
     *
     * <p><b>No window function.</b> A true per-group {@code row_number()} ranking
     * would be fairer, but Postgres rejects {@code FOR UPDATE} in a query with
     * window functions, and the workarounds cost the skip-locked property. That
     * trade is not worth it at this pool size.
     *
     * @param types job types this lane serves; empty means every type, which is
     *              what the single default lane uses
     */
    @Transactional
    public Optional<JobRecord> claim(Collection<String> types) {
        boolean allTypes = types == null || types.isEmpty();
        return jdbc.sql("""
                        update jobs
                        set status     = 'running',
                            claimed_at = now(),
                            claimed_by = :claimedBy,
                            updated_at = now()
                        where id = (
                            select id
                            from jobs
                            where status = 'queued'
                              and run_after <= now()
                              and (:allTypes = true or type in (:types))
                              and (
                                  group_id is null
                                  or group_id not in (
                                      select group_id from jobs
                                      where status = 'running' and group_id is not null
                                  )
                              )
                            order by priority desc, run_after, created_at
                            limit 1
                            for update skip locked
                        )
                        returning id, type, payload::text as payload, attempts, max_attempts, group_id
                        """)
                .param("claimedBy", properties.claimedBy())
                .param("allTypes", allTypes)
                // An empty IN list is a syntax error even when the guard above
                // short-circuits it, so feed the placeholder something harmless.
                .param("types", allTypes ? List.of("") : List.copyOf(types))
                .query((rs, rowNum) -> new JobRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        readPayload(rs.getString("payload")),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getString("group_id")))
                .optional();
    }

    @Transactional
    public void succeed(UUID jobId) {
        jdbc.sql("""
                        update jobs
                        set status = 'succeeded', last_error = null, updated_at = now()
                        where id = ?
                        """)
                .param(jobId)
                .update();
    }

    /** Terminal failure: out of attempts, or a {@link PermanentJobException}. */
    @Transactional
    public void fail(UUID jobId, String error) {
        jdbc.sql("""
                        update jobs
                        set status = 'failed', last_error = ?, updated_at = now()
                        where id = ?
                        """)
                .param(truncate(error))
                .param(jobId)
                .update();
    }

    /** Spends an attempt and backs off. */
    @Transactional
    public void retry(UUID jobId, int attemptsBefore, String error) {
        Instant next = Instant.now().plus(backoffFor(attemptsBefore + 1));
        jdbc.sql("""
                        update jobs
                        set status     = 'queued',
                            attempts   = attempts + 1,
                            run_after  = ?,
                            claimed_at = null,
                            claimed_by = null,
                            last_error = ?,
                            updated_at = now()
                        where id = ?
                        """)
                .param(java.sql.Timestamp.from(next))
                .param(truncate(error))
                .param(jobId)
                .update();
    }

    /**
     * Reschedules without spending an attempt — see {@link RetryAfterException}.
     */
    @Transactional
    public void retryAfter(UUID jobId, Duration delay, String reason) {
        jdbc.sql("""
                        update jobs
                        set status     = 'queued',
                            run_after  = ?,
                            claimed_at = null,
                            claimed_by = null,
                            last_error = ?,
                            updated_at = now()
                        where id = ?
                        """)
                .param(java.sql.Timestamp.from(Instant.now().plus(delay)))
                .param(truncate(reason))
                .param(jobId)
                .update();
    }

    /**
     * Returns jobs whose worker died to the queue.
     *
     * <p>Without this a crash mid-job strands the row in {@code running} forever,
     * and because the claim query excludes groups with a running job, that one
     * row would also block every other job for the same user — a permanent stall
     * from a single unlucky restart.
     *
     * <p>This does not spend an attempt: the process dying is not the job's
     * fault. A job that reliably kills its worker is instead bounded by
     * {@code max_attempts} the moment it throws rather than crashes.
     */
    @Transactional
    public int requeueStale() {
        int requeued = jdbc.sql("""
                        update jobs
                        set status     = 'queued',
                            claimed_at = null,
                            claimed_by = null,
                            last_error = 'requeued after stale claim',
                            updated_at = now()
                        where status = 'running'
                          and claimed_at < now() - (? * interval '1 second')
                        """)
                .param(properties.staleAfter().toSeconds())
                .update();

        if (requeued > 0) {
            log.warn("Requeued {} stale job(s) — a worker died holding them", requeued);
        }
        return requeued;
    }

    /**
     * Exponential backoff with a ceiling. Attempt 1 waits the base delay, and
     * each subsequent attempt quadruples it, so a 30s base gives 30s, 2m, 8m,
     * 32m, then the cap.
     */
    Duration backoffFor(int attempt) {
        Duration base = properties.retryBaseDelay();
        Duration max = properties.retryMaxDelay();
        // 4^(attempt-1), guarded against overflow on a pathological max_attempts.
        long multiplier = 1L << (2 * Math.min(Math.max(attempt, 1) - 1, 15));
        Duration delay = base.multipliedBy(multiplier);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, PAYLOAD_TYPE);
    }

    /** `last_error` is for operators, not storage — keep it bounded. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }
}
