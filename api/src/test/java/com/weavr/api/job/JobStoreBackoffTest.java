package com.weavr.api.job;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backoff schedule is arithmetic with no database in it, so it is worth
 * pinning exactly — a wrong exponent is the difference between retrying in two
 * minutes and retrying next week.
 */
class JobStoreBackoffTest {

    private static JobStore storeWith(Duration base, Duration max) {
        JobProperties properties = new JobProperties(
                true, 2, java.util.Map.of(), Duration.ofSeconds(2), "test", base, max, Duration.ofMinutes(15));
        // Only backoffFor is under test; it touches nothing but the properties.
        return new JobStore(null, null, properties);
    }

    @Test
    void quadruplesEachAttempt() {
        JobStore store = storeWith(Duration.ofSeconds(30), Duration.ofHours(1));

        assertThat(store.backoffFor(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(store.backoffFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(store.backoffFor(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(store.backoffFor(4)).isEqualTo(Duration.ofMinutes(32));
    }

    @Test
    void capsAtTheConfiguredMaximum() {
        JobStore store = storeWith(Duration.ofSeconds(30), Duration.ofHours(1));

        assertThat(store.backoffFor(5)).isEqualTo(Duration.ofHours(1));
        assertThat(store.backoffFor(50)).isEqualTo(Duration.ofHours(1));
    }

    /** A pathological max_attempts must not overflow the shift into a negative. */
    @Test
    void doesNotOverflowOnAbsurdAttemptCounts() {
        JobStore store = storeWith(Duration.ofSeconds(30), Duration.ofHours(1));

        assertThat(store.backoffFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void treatsAttemptZeroAsTheFirstAttempt() {
        JobStore store = storeWith(Duration.ofSeconds(30), Duration.ofHours(1));

        assertThat(store.backoffFor(0)).isEqualTo(Duration.ofSeconds(30));
    }
}
