package com.weavr.api.sync;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delta's boundary logic, which is the one place {@code GET /v1/sync} can
 * silently lose a row — and the only part of it that is testable without a
 * Postgres, so it is deliberately pure. {@link SyncService}'s queries are not
 * covered here (there is no local database; see CLAUDE.md), which is exactly
 * why the risky decision was moved out of them.
 */
class SyncWindowTest {

    private static final Instant SINCE = Instant.parse("2026-08-09T12:00:00Z");

    private static Instant at(long seconds) {
        return SINCE.plusSeconds(seconds);
    }

    @Test
    void anEmptyWindowLeavesTheCursorExactlyWhereItWas() {
        SyncWindow.Window window = SyncWindow.cap(List.of(List.of(), List.of()), SINCE, 200);

        // Not `now()`: the client's cursor must never drift forward past rows
        // that were written a microsecond after this query ran.
        assertThat(window.until()).isEqualTo(SINCE);
        assertThat(window.hasMore()).isFalse();
        assertThat(window.capped()).isFalse();
    }

    @Test
    void aWindowThatFitsEndsAtTheNewestRowSeenAcrossEveryType() {
        SyncWindow.Window window = SyncWindow.cap(
                List.of(List.of(at(1), at(4)), List.of(at(2)), List.of()),
                SINCE, 200);

        assertThat(window.until()).isEqualTo(at(4));
        assertThat(window.hasMore()).isFalse();
        assertThat(window.capped()).isFalse();
    }

    /**
     * One type overflowing caps the whole page, including types that had far
     * fewer rows — otherwise the single shared cursor would advance past rows
     * the overflowing type has not sent yet.
     */
    @Test
    void oneOverflowingTypeCapsThePageForEveryType() {
        // limit 2, so three rows back means "there is more".
        SyncWindow.Window window = SyncWindow.cap(
                List.of(List.of(at(1), at(2), at(3)), List.of(at(9))),
                SINCE, 2);

        assertThat(window.until()).isEqualTo(at(2));
        assertThat(window.hasMore()).isTrue();
        assertThat(window.capped()).isTrue();
    }

    /**
     * The cap is the <em>smallest</em> boundary, not the largest. Taking the
     * largest would advance the cursor past rows the other overflowing type
     * still owes — the silent-loss bug this class exists to prevent.
     */
    @Test
    void theCapIsTheEarliestBoundaryAmongOverflowingTypes() {
        SyncWindow.Window window = SyncWindow.cap(
                List.of(
                        List.of(at(10), at(20), at(30)),
                        List.of(at(1), at(2), at(3))),
                SINCE, 2);

        assertThat(window.until()).isEqualTo(at(2));
    }

    /**
     * The reason the cursor is a timestamp and not a keyset: rows written in one
     * transaction share a timestamp to the microsecond. A page must close on a
     * whole-timestamp boundary, so when every row that fits shares one instant
     * the cap is that instant — and {@code capped} tells the service to re-read
     * with an inclusive bound, which is what pulls in the rest of the group.
     */
    @Test
    void aPageWhoseRowsAllShareOneTimestampCapsAtThatTimestamp() {
        SyncWindow.Window window = SyncWindow.cap(
                List.of(List.of(at(5), at(5), at(5))),
                SINCE, 2);

        assertThat(window.until()).isEqualTo(at(5));
        assertThat(window.capped()).isTrue();
        // Strictly after `since`, so the client cannot ask for the same window
        // again — which is what guarantees progress even in the degenerate case
        // where a single timestamp group is larger than the page size.
        assertThat(window.until()).isAfter(SINCE);
    }

    /** Every fetched row is `> since`, so a cap can never move the cursor backwards. */
    @Test
    void theCursorNeverMovesBackwards() {
        SyncWindow.Window capped = SyncWindow.cap(
                List.of(List.of(at(1), at(2), at(3))), SINCE, 1);
        assertThat(capped.until()).isAfter(SINCE);

        SyncWindow.Window complete = SyncWindow.cap(List.of(List.of(at(7))), SINCE, 200);
        assertThat(complete.until()).isAfter(SINCE);
    }

    /** Exactly `limit` rows is not an overflow — the extra row is the signal. */
    @Test
    void exactlyLimitRowsIsNotAnOverflow() {
        SyncWindow.Window window = SyncWindow.cap(List.of(List.of(at(1), at(2))), SINCE, 2);

        assertThat(window.hasMore()).isFalse();
        assertThat(window.until()).isEqualTo(at(2));
    }

    @Test
    void aLimitBelowOneIsRejectedRatherThanSilentlyLoopingForever() {
        assertThatThrownBy(() -> SyncWindow.cap(List.of(List.of(at(1))), SINCE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
