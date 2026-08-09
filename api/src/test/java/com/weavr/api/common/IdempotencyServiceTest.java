package com.weavr.api.common;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcClient} mocked, as everywhere else in this codebase that has no
 * local Postgres to talk to (see CLAUDE.md). What that leaves is exactly the
 * part that is Java rather than SQL, and it is the part with the interesting
 * decisions in it: whether the work runs, what a replay returns, and which
 * failures are retryable.
 *
 * <p>The one behaviour these cannot reach is the concurrency the design leans
 * on — a second inserter blocking on the first's uncommitted row inside
 * {@code on conflict do nothing}, then reading the committed response. That is
 * Postgres's behaviour, not this class's, and the mock can only stand in for
 * its outcome (a claim that affects zero rows).
 */
class IdempotencyServiceTest {

    private static final String ENDPOINT = "POST /v1/spaces";

    /** A stand-in for the records the three real call sites return. */
    record Thing(String id, String name) {
    }

    private JdbcClient jdbc;
    private IdempotencyService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        service = new IdempotencyService(jdbc, JsonMapper.builder().build());
        userId = UUID.randomUUID();
    }

    /**
     * The app's own Paste Link tile has never sent one, and the contract for a
     * caller that does not is the same as {@code SaveService.create}'s: the work
     * runs, with no protection and no bookkeeping.
     */
    @Test
    void noKeyRunsTheWorkAndTouchesNothing() {
        AtomicInteger runs = new AtomicInteger();

        Thing result = service.execute(userId, null, ENDPOINT, Thing.class, () -> {
            runs.incrementAndGet();
            return new Thing("a", "Trip");
        });

        assertThat(result).isEqualTo(new Thing("a", "Trip"));
        assertThat(runs).hasValue(1);
        verifyNoInteractions(jdbc);
    }

    /** A blank header is the same as no header, not a key of its own. */
    @Test
    void aBlankKeyIsTreatedAsNoKey() {
        service.execute(userId, "   ", ENDPOINT, Thing.class, () -> new Thing("a", "Trip"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void anUnusedKeyClaimsRunsTheWorkAndStoresTheResponse() {
        JdbcClient.StatementSpec claim = stubClaim(1);
        JdbcClient.StatementSpec store = stubStore();

        Thing result = service.execute(userId, "k-1", ENDPOINT, Thing.class,
                () -> new Thing("a", "Trip"));

        assertThat(result).isEqualTo(new Thing("a", "Trip"));
        verify(claim).update();
        verify(store).update();
    }

    /**
     * The whole point: the second attempt returns the <em>first</em> attempt's
     * answer. Recording only that the key was seen and replying 204 would leave
     * a retried invite creation "successful" and the caller still without the
     * code the request exists to produce.
     */
    @Test
    void aReplayReturnsTheStoredResponseAndNeverRunsTheWorkAgain() {
        stubClaim(0);
        stubLookup(Optional.of(new IdempotencyService.StoredResponse(
                ENDPOINT, "{\"id\":\"a\",\"name\":\"Trip\"}")));
        AtomicInteger runs = new AtomicInteger();

        Thing result = service.execute(userId, "k-1", ENDPOINT, Thing.class, () -> {
            runs.incrementAndGet();
            return new Thing("b", "A second Trip");
        });

        assertThat(result).isEqualTo(new Thing("a", "Trip"));
        assertThat(runs).hasValue(0);
    }

    /**
     * A claim with no response yet. Unreachable through the single-transaction
     * flow — the claim and the response commit together — but it is what a
     * hand-inserted row or a future {@code REQUIRES_NEW} claim would look like,
     * and 409 is the honest answer: ask again.
     */
    @Test
    void aClaimWithNoStoredResponseIsAConflictRatherThanASecondAttempt() {
        stubClaim(0);
        stubLookup(Optional.of(new IdempotencyService.StoredResponse(ENDPOINT, null)));
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(userId, "k-1", ENDPOINT, Thing.class, () -> {
            runs.incrementAndGet();
            return new Thing("b", "Trip");
        })).isInstanceOf(IdempotencyService.ConflictException.class);

        assertThat(runs).hasValue(0);
    }

    /**
     * The claim was rolled back between our insert and the read — the first
     * attempt failed. Retrying is right, so this is a 409 rather than a 4xx the
     * outbox would surface as terminal.
     */
    @Test
    void aVanishedClaimIsAConflict() {
        stubClaim(0);
        stubLookup(Optional.empty());

        assertThatThrownBy(() -> service.execute(userId, "k-1", ENDPOINT, Thing.class,
                () -> new Thing("b", "Trip")))
                .isInstanceOf(IdempotencyService.ConflictException.class);
    }

    /**
     * A key reused across two endpoints is a client bug, and running the second
     * request anyway would defeat the mechanism at the moment it was asked to
     * work. 400, which the outbox treats as terminal and surfaces — retrying
     * cannot fix it.
     */
    @Test
    void aKeyReusedOnADifferentEndpointIsRejectedRatherThanReplayed() {
        stubClaim(0);
        stubLookup(Optional.of(new IdempotencyService.StoredResponse(
                "POST /v1/saves/{id}/comments", "{\"id\":\"a\",\"name\":\"Trip\"}")));

        assertThatThrownBy(() -> service.execute(userId, "k-1", ENDPOINT, Thing.class,
                () -> new Thing("b", "Trip")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("different request");
    }

    /**
     * The failure propagates and the response is never written, so the claim
     * rolls back with the work and a later retry finds nothing — which is the
     * reason the claim lives in the caller's transaction rather than its own.
     */
    @Test
    void aFailedWorkUnitStoresNothingAndPropagates() {
        stubClaim(1);
        JdbcClient.StatementSpec store = stubStore();

        assertThatThrownBy(() -> service.execute(userId, "k-1", ENDPOINT, Thing.class, () -> {
            throw new IllegalStateException("the space could not be created");
        })).isInstanceOf(IllegalStateException.class);

        verify(store, never()).update();
    }

    // ------------------------------------------------------------- stubbing

    private JdbcClient.StatementSpec stubClaim(int rowsAffected) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into idempotency_keys"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(rowsAffected);
        return spec;
    }

    private JdbcClient.StatementSpec stubStore() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("update idempotency_keys"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private void stubLookup(Optional<IdempotencyService.StoredResponse> row) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("select endpoint"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        JdbcClient.MappedQuerySpec<IdempotencyService.StoredResponse> mapped =
                mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.optional()).thenReturn(row);
    }
}
