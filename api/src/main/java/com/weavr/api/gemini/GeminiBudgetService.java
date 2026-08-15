package com.weavr.api.gemini;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import com.weavr.api.job.RetryAfterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Guards the Gemini daily-request budget and mints {@link BudgetApproved}
 * tokens for callers that are under quota.
 *
 * <p>Google resets quotas at midnight US/Pacific — counters must use the same
 * timezone, or a deploy near UTC midnight looks like two different days and
 * silently overspends the real limit.
 *
 * <p>The counter lives in {@code ai_budget_days}. An in-memory counter loses
 * the day's consumption on every deploy and restarts silently over-spending.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Try the primary model. If under budget, approve it.</li>
 *   <li>If primary is exhausted, try the fallback model.</li>
 *   <li>If both are exhausted, throw {@link RetryAfterException} — no attempt
 *       is spent, the save parks until tomorrow.</li>
 * </ol>
 */
@Component
public class GeminiBudgetService {

    private static final Logger log = LoggerFactory.getLogger(GeminiBudgetService.class);

    /** Google's reset boundary. */
    static final ZoneId GOOGLE_RESET_TZ = ZoneId.of("America/Los_Angeles");

    private final JdbcClient jdbc;
    private final GeminiProperties props;
    private final ModelRateLimiter rateLimiter;

    GeminiBudgetService(JdbcClient jdbc, GeminiProperties props, ModelRateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.props = props;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Per-minute gate, applied after the daily counter has been claimed.
     *
     * <p>Order matters and this way round is the safe one. The daily increment
     * is the scarce, durable resource and it is transactional; the minute wait
     * is in-process and free. Waiting first would mean holding a worker for
     * seconds only to discover the day's budget was gone — and waiting inside
     * the surrounding {@code @Transactional} keeps a pooled connection open for
     * the duration, which a five-connection pool cannot afford.
     *
     * <p>Note the consequence, stated rather than hidden: if the wait exceeds
     * the cap and this throws, the day's counter has <em>already</em> been
     * incremented for a request that was never sent. That over-counts by at
     * most the number of in-flight callers and errs toward under-spending the
     * quota, which is the right direction to be wrong in.
     */
    private void awaitSlot(String model, int rpm) {
        rateLimiter.acquire(model, rpm, props.rateLimitMaxWait());
    }

    /**
     * Tries to acquire budget for the primary model, falling back to the
     * fallback model if the primary pool is exhausted.
     *
     * @return a {@link BudgetApproved} token containing the approved model
     * @throws RetryAfterException if both daily pools are exhausted; the job
     *         runner will re-schedule the save for tomorrow without spending
     *         an attempt
     */
    /**
     * Deliberately <b>not</b> {@code @Transactional} any more.
     *
     * <p>It never needed to be: the guard is entirely inside the single
     * {@code UPDATE ... WHERE requests_used < limit}, which is atomic on its
     * own, and the {@code INSERT ... ON CONFLICT DO NOTHING} before it is
     * atomic too. Two autocommitted statements are exactly as correct here as
     * one transaction.
     *
     * <p>What the change buys is that {@link #awaitSlot} can sleep without
     * holding a pooled connection — with a five-connection pool shared between
     * HTTP and jobs, sleeping inside a transaction is how a rate limiter turns
     * into an outage.
     */
    public BudgetApproved acquire() {
        LocalDate today = LocalDate.now(GOOGLE_RESET_TZ);

        // Try primary first.
        if (tryIncrement(today, props.primaryModel(), props.primaryRpd())) {
            log.debug("Budget approved: primary model {} for {}", props.primaryModel(), today);
            awaitSlot(props.primaryModel(), props.primaryRpm());
            return new BudgetApproved(props.primaryModel(), props.primaryRpd());
        }

        // Primary exhausted — try fallback.
        if (tryIncrement(today, props.fallbackModel(), props.fallbackRpd())) {
            log.warn("Primary model {} exhausted for {}, falling back to {}",
                    props.primaryModel(), today, props.fallbackModel());
            awaitSlot(props.fallbackModel(), props.fallbackRpm());
            return new BudgetApproved(props.fallbackModel(), props.fallbackRpd());
        }

        // Both pools exhausted.
        log.warn("Both Gemini model pools exhausted for {}. Saves will park until tomorrow.", today);
        Duration untilReset = durationUntilReset(today);
        throw new RetryAfterException(
                "Daily Gemini budget exhausted — save will be processed tomorrow.",
                untilReset);
    }

    /**
     * Tries to acquire budget for a specific model (used by
     * {@link com.weavr.api.save.ClassifySaveHandler} to attempt a fallback
     * call when primary confidence is too low).
     *
     * @param model the specific model to acquire budget for
     * @param rpd   its daily ceiling
     * @return a {@link BudgetApproved} token, or empty if exhausted
     */
    /** Not transactional, for the same reason as {@link #acquire()}. */
    public BudgetApproved acquireFor(String model, int rpd) {
        LocalDate today = LocalDate.now(GOOGLE_RESET_TZ);
        if (tryIncrement(today, model, rpd)) {
            // The caller names a specific model, so pick its matching per-minute
            // ceiling rather than assuming the primary's — the fallback's is a
            // third of it, and both consumers of `acquireFor` use the fallback.
            awaitSlot(model, model.equals(props.primaryModel()) ? props.primaryRpm() : props.fallbackRpm());
            return new BudgetApproved(model, rpd);
        }
        Duration untilReset = durationUntilReset(today);
        throw new RetryAfterException(
                "Daily Gemini budget exhausted — save will be processed tomorrow.",
                untilReset);
    }

    /**
     * Attempts an atomic increment of the counter for (date, model).
     * Returns {@code true} if the increment succeeded (we are under quota),
     * {@code false} if the row already hit the ceiling.
     *
     * <p>Uses an upsert with a WHERE guard: the update only fires when
     * {@code requests_used < limit}. If it returns 0 rows, quota is full.
     */
    private boolean tryIncrement(LocalDate date, String model, int limit) {
        // Insert the row if this is the first request for this model today.
        jdbc.sql("""
                        insert into ai_budget_days (usage_date, model, requests_used)
                        values (?, ?, 0)
                        on conflict (usage_date, model) do nothing
                        """)
                .param(date)
                .param(model)
                .update();

        int updated = jdbc.sql("""
                        update ai_budget_days
                        set requests_used = requests_used + 1,
                            updated_at    = now()
                        where usage_date    = ?
                          and model         = ?
                          and requests_used < ?
                        """)
                .param(date)
                .param(model)
                .param(limit)
                .update();

        return updated > 0;
    }

    /** How long until Google resets the quota (midnight US/Pacific). */
    private static Duration durationUntilReset(LocalDate today) {
        // Tomorrow midnight Pacific.
        var tomorrowMidnight = today.plusDays(1).atStartOfDay(GOOGLE_RESET_TZ).toInstant();
        var now = java.time.Instant.now();
        long seconds = java.time.Duration.between(now, tomorrowMidnight).getSeconds();
        // Add a 5-minute buffer so we don't hit exactly on the boundary.
        return Duration.ofSeconds(Math.max(seconds + 300, 300));
    }
}
