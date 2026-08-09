package com.weavr.api.common;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes a non-replay-safe write replay-safe, keyed on a caller-supplied
 * {@code Idempotency-Key}.
 *
 * <p>Three endpoints need this and no others: {@code POST /v1/spaces},
 * {@code POST /v1/spaces/{id}/invites} and
 * {@code POST /v1/saves/{id}/comments}. Each inserts a row with a
 * server-generated id, so sending it twice creates two of them — and L3's
 * outbox retries any request whose response was lost, which is precisely the
 * case that produces a duplicate. Everything else the app writes is already an
 * absolute set ({@code PATCH .../flags}, {@code PUT .../vote},
 * {@code PATCH /v1/entity-state}), which is why last-write-wins is the outbox's
 * real semantics rather than a compromise, and why those endpoints need nothing
 * from this class.
 *
 * <h2>The stored response is the point, not the avoided write</h2>
 *
 * <p>It would be simpler to record only that the key was seen and answer a
 * replay with {@code 204}. That leaves the caller unable to finish an operation
 * the server has already completed: a retried invite creation would "succeed"
 * and the client would still not have the code it exists to obtain. So the
 * first attempt's response body is stored and replayed verbatim.
 *
 * <h2>Why one transaction, and what a concurrent duplicate actually does</h2>
 *
 * <p>The claim insert, the work and the response write are all in the caller's
 * transaction. That is what makes a failure clean: the work rolls back and so
 * does the claim, so a retry finds nothing and genuinely retries rather than
 * being told forever that a request which never happened is in flight.
 *
 * <p>The concurrency this creates is better than the alternative rather than a
 * cost of it. A second request carrying the same key blocks on the first's
 * uncommitted row inside {@code on conflict do nothing}; when the first commits,
 * the second's insert affects no rows and — because READ COMMITTED takes a fresh
 * snapshot per statement — its follow-up {@code select} sees the stored
 * response and replays it. When the first rolls back, the second's insert
 * succeeds and it does the work. Both are the correct answer, reached with no
 * polling and no retry loop.
 *
 * <p>{@link ConflictException} therefore covers a case the ordinary flow cannot
 * reach: a claim row that exists with no response. It is kept because the
 * invariant is worth stating and because it is what a future
 * {@code REQUIRES_NEW} claim (or a hand-inserted row) would produce — a 409 the
 * outbox will retry, which is the honest answer to "somebody else is doing this
 * right now".
 *
 * <p><b>{@code POST /v1/saves} keeps its own mechanism</b> — a column plus a
 * partial unique index, since V2. It is live-verified including the concurrent
 * race, and moving it here would be schema risk for no behavioural gain. The
 * inconsistency is deliberate and is recorded in {@code V16__idempotency.sql}
 * and {@code docs/local-first.md} as well as here, because it is exactly the
 * kind of thing a later reader tidies up.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * Two requests carrying one key are in flight, or a stored claim never got
     * its response. Neither is the caller's fault and both resolve by asking
     * again, so this is a 409 rather than a 4xx the outbox would treat as
     * terminal.
     */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * What a used key has recorded against it. A nested type rather than a
     * local one so a test can build the row the mapper would have produced —
     * there is no local Postgres here to produce a real one (see CLAUDE.md).
     *
     * @param body the stored response as JSON text, or null for a claim whose
     *             work has not finished
     */
    record StoredResponse(String endpoint, String body) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code work} at most once per {@code (userId, key)}, replaying the
     * first run's result on any later call.
     *
     * @param key      the {@code Idempotency-Key} header, or null/blank when the
     *                 caller sent none — in which case the work simply runs, with
     *                 no protection, the same contract
     *                 {@code SaveService.create} offers
     * @param endpoint a stable name for the call site, so a key reused across two
     *                 different endpoints is reported rather than silently
     *                 replaying the wrong body
     * @param type     the response record, needed to read the stored JSON back
     */
    @Transactional
    public <T> T execute(UUID userId, String key, String endpoint, Class<T> type, Supplier<T> work) {
        String trimmed = key == null || key.isBlank() ? null : key.trim();
        if (trimmed == null) {
            return work.get();
        }

        int claimed = jdbc.sql("""
                        insert into idempotency_keys (user_id, key, endpoint)
                        values (?, ?, ?)
                        on conflict (user_id, key) do nothing
                        """)
                .param(userId)
                .param(trimmed)
                .param(endpoint)
                .update();

        if (claimed == 0) {
            return replay(userId, trimmed, endpoint, type);
        }

        T result = work.get();

        jdbc.sql("""
                        update idempotency_keys
                        set status_code = ?, response_body = ?::jsonb
                        where user_id = ? and key = ?
                        """)
                .param(200)
                .param(objectMapper.writeValueAsString(result))
                .param(userId)
                .param(trimmed)
                .update();

        return result;
    }

    /**
     * The stored answer for a key that has been used before.
     *
     * <p>An endpoint mismatch is a client bug, not a conflict, so it is a 400
     * ({@link BadRequestException}) rather than a 409 — which the outbox treats
     * as terminal and surfaces. That is the right outcome: no amount of retrying
     * fixes a key the client reused, and quietly running the second request
     * anyway would defeat the mechanism at exactly the moment it was asked to
     * work.
     */
    private <T> T replay(UUID userId, String key, String endpoint, Class<T> type) {
        Optional<StoredResponse> stored = jdbc
                .sql("select endpoint, response_body::text as body from idempotency_keys where user_id = ? and key = ?")
                .param(userId)
                .param(key)
                .query((rs, row) -> new StoredResponse(rs.getString("endpoint"), rs.getString("body")))
                .optional();

        if (stored.isEmpty()) {
            // The claim was rolled back between our insert and this read — the
            // first attempt failed. Asking again is the right move and is what
            // the outbox does.
            throw new ConflictException("That request is still being processed. Try again in a moment.");
        }
        if (!endpoint.equals(stored.get().endpoint())) {
            throw new BadRequestException(
                    "This Idempotency-Key was already used for a different request.");
        }
        if (stored.get().body() == null) {
            throw new ConflictException("That request is still being processed. Try again in a moment.");
        }

        log.info("Idempotent replay of {} key={} user={}", endpoint, key, userId);
        return objectMapper.readValue(stored.get().body(), type);
    }
}
