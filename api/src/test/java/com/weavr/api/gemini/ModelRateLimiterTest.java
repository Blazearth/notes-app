package com.weavr.api.gemini;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.weavr.api.job.RetryAfterException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-minute limiter that had to ship alongside the lane split.
 *
 * <p>Without it, raising the fast lane to 4 workers puts roughly 30 classify
 * calls a minute against a 15 RPM ceiling — and exceeding it does not queue, it
 * 429s, which costs a retry attempt and a 30-second backoff. So the limiter is
 * not a nicety on top of more concurrency; it is what makes more concurrency an
 * improvement rather than a different failure.
 */
class ModelRateLimiterTest {

    private final ModelRateLimiter limiter = new ModelRateLimiter();

    /** The bucket starts full, so a cold instance never pays a warm-up penalty. */
    @Test
    void theFirstBurstUpToTheLimitIsNotDelayed() {
        for (int i = 0; i < 15; i++) {
            Duration waited = limiter.acquire("flash-lite", 15, Duration.ofMillis(50));
            assertThat(waited).isLessThan(Duration.ofMillis(50));
        }
    }

    /**
     * Past the burst the caller is made to wait — that is the entire point. At
     * 60 RPM a token accrues each second, so the 61st call cannot be immediate.
     */
    @Test
    void exhaustingTheBucketForcesAWait() {
        for (int i = 0; i < 60; i++) {
            limiter.acquire("m", 60, Duration.ofSeconds(5));
        }

        long start = System.nanoTime();
        limiter.acquire("m", 60, Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // ~1000ms at 60 RPM; allow generous slack for a loaded CI machine.
        assertThat(elapsedMs).isGreaterThan(100);
    }

    /**
     * The design decision worth pinning: a caller facing a long wait hands the
     * job back to the queue instead of sleeping on one of a handful of worker
     * slots. {@link RetryAfterException} costs no attempt, so this is free.
     */
    @Test
    void aWaitLongerThanTheCapRequeuesInsteadOfBlocking() {
        // 1 RPM: one token, then a full minute for the next.
        limiter.acquire("slow", 1, Duration.ofMillis(10));

        assertThatThrownBy(() -> limiter.acquire("slow", 1, Duration.ofMillis(10)))
                .isInstanceOf(RetryAfterException.class);
    }

    /** Models have separate pools; spending the primary must not throttle the fallback. */
    @Test
    void eachModelHasItsOwnBudget() {
        for (int i = 0; i < 15; i++) {
            limiter.acquire("primary", 15, Duration.ofMillis(50));
        }

        // The fallback's own bucket is untouched.
        Duration waited = limiter.acquire("fallback", 5, Duration.ofMillis(50));
        assertThat(waited).isLessThan(Duration.ofMillis(50));
    }

    /** A non-positive limit disables the gate rather than deadlocking on zero tokens. */
    @Test
    void aZeroLimitIsTreatedAsUnlimitedRatherThanBlockingForever() {
        assertThat(limiter.acquire("m", 0, Duration.ofMillis(10))).isEqualTo(Duration.ZERO);
    }

    /**
     * The real usage: several fast-lane workers hitting one model at once. The
     * bucket must be shared, not per-thread — a per-thread limiter would permit
     * exactly the burst this exists to prevent.
     */
    @Test
    void concurrentCallersShareOneBudget() throws Exception {
        int permits = 10;
        AtomicInteger immediate = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tasks.add(() -> {
                    try {
                        Duration waited = limiter.acquire("shared", permits, Duration.ofMillis(120));
                        if (waited.toMillis() < 60) {
                            immediate.incrementAndGet();
                        }
                    } catch (RetryAfterException expected) {
                        // Requeued rather than served — also a correct outcome.
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        }

        // 20 callers, a 10-token bucket: at most ~the bucket size can be served
        // without waiting. The assertion is deliberately loose on the upper
        // side (timing) but strict that it is not simply "all 20".
        assertThat(immediate.get()).isLessThan(20);
        assertThat(immediate.get()).isGreaterThanOrEqualTo(1);
    }

    /** Tokens accrue continuously, so waiting genuinely buys capacity back. */
    @Test
    void tokensRefillOverTime() throws Exception {
        for (int i = 0; i < 60; i++) {
            limiter.acquire("refill", 60, Duration.ofSeconds(2));
        }

        Thread.sleep(1100);   // ~1 token back at 60 RPM

        Duration waited = limiter.acquire("refill", 60, Duration.ofMillis(200));
        assertThat(waited).isLessThan(Duration.ofMillis(200));
    }
}
