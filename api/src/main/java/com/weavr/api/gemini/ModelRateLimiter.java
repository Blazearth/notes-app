package com.weavr.api.gemini;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-model requests-per-minute limiter — the constraint {@link
 * GeminiBudgetService} never enforced.
 *
 * <p>Only requests-per-<em>day</em> was tracked, which was harmless while the
 * job runner ran one job at a time: a single classify call at a time cannot
 * exceed 15/min however hard it tries. With the fast lane several slots wide
 * that stops being true — 4 workers at roughly 8s a call is ~30 requests/minute
 * against a verified 15 RPM ceiling on the primary model and 5 RPM on the
 * fallback.
 *
 * <p><b>Why this is not optional alongside the lane split.</b> Exceeding the
 * limit does not queue, it 429s. A 429 becomes a {@link
 * com.weavr.api.job.RetryableJobException}, which <em>spends an attempt</em> and
 * backs off 30 seconds. So raising concurrency without this converts queue delay
 * into retry delay and burns the retry budget on the way — strictly worse than
 * having waited.
 *
 * <h2>Wait briefly, then hand the job back</h2>
 *
 * <p>A caller that would have to wait a long time does not block a worker
 * thread for it: past {@code maxWait} this throws {@link
 * com.weavr.api.job.RetryAfterException} so the job returns to the queue
 * <em>without</em> spending an attempt, freeing the slot for a job whose own
 * rate limit is not exhausted. Blocking would pin a scarce worker on a sleep.
 *
 * <h2>In-process, deliberately</h2>
 *
 * <p>Unlike the daily budget — which lives in {@code ai_budget_days} because it
 * must survive a restart, or a deploy silently re-spends the day's quota — a
 * per-minute allowance is worth almost nothing across a restart: the restart
 * itself takes most of a minute. A database-backed minute counter would add a
 * write to every model call to protect against an overshoot that resolves
 * itself in under 60 seconds.
 *
 * <p><b>The limitation this accepts, stated rather than hidden:</b> it does not
 * coordinate across instances. Two processes sharing one API key — a local run
 * and the Render deploy, which this project does routinely — can together
 * exceed the limit. Their 429s are still handled correctly, just not prevented.
 * The single-instance deployment is the one this protects.
 */
@Component
public class ModelRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ModelRateLimiter.class);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Blocks until a request slot for {@code model} is free.
     *
     * @param rpm     requests permitted per minute for this model
     * @param maxWait longest this call will block before giving up
     * @return how long it waited, for logging
     * @throws com.weavr.api.job.RetryAfterException if a slot would take longer
     *                                               than {@code maxWait}
     */
    public Duration acquire(String model, int rpm, Duration maxWait) {
        if (rpm <= 0) {
            return Duration.ZERO;
        }
        TokenBucket bucket = buckets.computeIfAbsent(model, m -> new TokenBucket(rpm));
        Duration waited = bucket.acquire(rpm, maxWait);
        if (waited.compareTo(Duration.ofMillis(500)) > 0) {
            log.info("Rate limiter held a {} request for {}ms ({} RPM)", model, waited.toMillis(), rpm);
        }
        return waited;
    }

    /** Visible for testing — forgets all accumulated state. */
    void reset() {
        buckets.clear();
    }

    /**
     * A minute-granularity token bucket.
     *
     * <p>Refills continuously rather than resetting on a minute boundary: a
     * fixed window lets 15 requests fire at 00:59 and 15 more at 01:00, which is
     * 30 inside one actual minute and exactly the burst the provider counts.
     */
    private static final class TokenBucket {

        private final Object lock = new Object();
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int rpm) {
            this.tokens = rpm;
            this.lastRefillNanos = System.nanoTime();
        }

        Duration acquire(int rpm, Duration maxWait) {
            long startedAt = System.nanoTime();
            long deadline = startedAt + maxWait.toNanos();

            while (true) {
                long sleepNanos;
                synchronized (lock) {
                    refill(rpm);
                    if (tokens >= 1.0) {
                        tokens -= 1.0;
                        return Duration.ofNanos(System.nanoTime() - startedAt);
                    }
                    // Time until one whole token accrues.
                    double needed = 1.0 - tokens;
                    sleepNanos = (long) (needed * 60_000_000_000L / rpm);
                }

                if (System.nanoTime() + sleepNanos > deadline) {
                    throw new com.weavr.api.job.RetryAfterException(
                            "Model rate limit reached — requeued rather than holding a worker.",
                            Duration.ofNanos(Math.max(sleepNanos, 1_000_000_000L)));
                }
                try {
                    Thread.sleep(Math.max(sleepNanos / 1_000_000, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new com.weavr.api.job.RetryAfterException(
                            "Interrupted waiting on the model rate limit.", Duration.ofSeconds(30));
                }
            }
        }

        /** Caller holds {@link #lock}. */
        private void refill(int rpm) {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            lastRefillNanos = now;
            tokens = Math.min(rpm, tokens + (double) elapsed * rpm / 60_000_000_000L);
        }
    }
}
