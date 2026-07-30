package com.weavr.api.gemini;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import com.weavr.api.job.RetryAfterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    GeminiBudgetService(JdbcClient jdbc, GeminiProperties props) {
        this.jdbc = jdbc;
        this.props = props;
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
    @Transactional
    public BudgetApproved acquire() {
        LocalDate today = LocalDate.now(GOOGLE_RESET_TZ);

        // Try primary first.
        if (tryIncrement(today, props.primaryModel(), props.primaryRpd())) {
            log.debug("Budget approved: primary model {} for {}", props.primaryModel(), today);
            return new BudgetApproved(props.primaryModel(), props.primaryRpd());
        }

        // Primary exhausted — try fallback.
        if (tryIncrement(today, props.fallbackModel(), props.fallbackRpd())) {
            log.warn("Primary model {} exhausted for {}, falling back to {}",
                    props.primaryModel(), today, props.fallbackModel());
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
    @Transactional
    public BudgetApproved acquireFor(String model, int rpd) {
        LocalDate today = LocalDate.now(GOOGLE_RESET_TZ);
        if (tryIncrement(today, model, rpd)) {
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
