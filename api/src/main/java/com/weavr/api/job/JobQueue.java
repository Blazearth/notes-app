package com.weavr.api.job;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Enqueue side of the Postgres-backed job queue.
 *
 * <p>The claim side ({@code SELECT ... FOR UPDATE SKIP LOCKED}, retry handling,
 * {@code group_id} round-robin) lands with the job runner in Phase 2. This class
 * exists now so {@code POST /v1/saves} can hand work off and return 202.
 */
@Component
public class JobQueue {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JobQueue(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a queued job, or does nothing if {@code idempotencyKey} is already
     * present.
     *
     * <p>The dedupe matters at both ends: a share extension retrying a
     * background upload must not produce two pipeline runs, and neither must a
     * user re-sharing the same Reel.
     *
     * <p>Called inside the caller's transaction, so the job is only visible if
     * the row it refers to committed.
     *
     * @param groupId round-robin key — the user id, so one user cannot
     *                monopolise a worker pool capped at 1–2
     * @return true if a job was enqueued, false if it was a duplicate
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean enqueue(String type, Object payload, String idempotencyKey, String groupId, int priority) {
        int inserted = jdbc.sql("""
                        insert into jobs (type, payload, idempotency_key, group_id, priority)
                        values (?, ?::jsonb, ?, ?, ?)
                        on conflict (idempotency_key) do nothing
                        """)
                .param(type)
                .param(toJson(payload))
                .param(idempotencyKey)
                .param(groupId)
                .param(priority)
                .update();
        return inserted > 0;
    }

    /** Convenience for the common case: normal priority, grouped by user. */
    public boolean enqueueForUser(String type, Object payload, String idempotencyKey, UUID userId) {
        return enqueue(type, payload, idempotencyKey, userId.toString(), 0);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Unchecked in Jackson 3, but caught deliberately: a payload that
            // cannot serialise is a programming error, and failing here beats
            // writing a job the runner can never read.
            throw new IllegalArgumentException("Job payload is not serialisable: " + payload.getClass(), e);
        }
    }
}
