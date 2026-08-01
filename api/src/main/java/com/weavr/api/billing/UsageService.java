package com.weavr.api.billing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The free-tier caps: 20 AI saves a month, 1 Act a week.
 *
 * <p>These exist to stop one user starving everyone else. On the free Gemini
 * tier the cost of a save is not money, it is a slice of a shared 500-a-day
 * request pool — so an unmetered heavy user is not an expensive customer, they
 * are an outage for everybody.
 *
 * <h2>Why two periods share one table</h2>
 * <p>{@code usage_counters} is keyed {@code (user_id, period_start)} and carries
 * both counters, which reads like a bug the first time: the two caps have
 * different periods, so they cannot share a row. They do not have to. Each
 * counter is written and read at <em>its own</em> {@code period_start} — the
 * first of the month for saves, the ISO-week Monday for Acts — and only ever
 * touches its own column. The other column on each row stays zero and is never
 * read. On the rare date where a Monday is also the first of the month the two
 * land on one row, and both are still correct, because neither reads the other.
 *
 * <p>Periods are UTC. A user's own midnight is unknowable from a JWT, and the
 * alternative — a rolling window — would mean the cap resets at a different
 * time for every user and cannot be explained in a sentence on a paywall.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    public static final String QUOTA_SAVES = "saves";
    public static final String QUOTA_ACTS = "acts";

    /**
     * @param allowed whether the caller may proceed
     * @param limit   the cap that applied, or {@code -1} when unlimited (Pro,
     *                or enforcement switched off)
     */
    public record Allowance(boolean allowed, String quota, int limit, int used) {

        static Allowance unlimited(String quota, int used) {
            return new Allowance(true, quota, -1, used);
        }

        public boolean unlimited() {
            return limit < 0;
        }
    }

    /** What {@code GET /v1/me} reports. */
    public record UsageSummary(int savesUsed, int savesLimit, int actsUsed, int actsLimit) {
    }

    private final JdbcClient jdbc;
    private final BillingProperties props;
    private final EntitlementService entitlements;

    UsageService(JdbcClient jdbc, BillingProperties props, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.props = props;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public Allowance checkSaves(UUID userId) {
        return check(userId, QUOTA_SAVES, "saves_used", monthStart(), props.freeSavesPerMonth());
    }

    @Transactional(readOnly = true)
    public Allowance checkActs(UUID userId) {
        return check(userId, QUOTA_ACTS, "acts_used", weekStart(), props.freeActsPerWeek());
    }

    private Allowance check(UUID userId, String quota, String column, LocalDate periodStart, int limit) {
        int used = read(userId, column, periodStart);
        if (!props.enforceFreeCaps() || entitlements.isPro(userId)) {
            return Allowance.unlimited(quota, used);
        }
        return new Allowance(used < limit, quota, limit, used);
    }

    /**
     * Counts one AI save against this month's allowance.
     *
     * <p>Called after the Gemini request has already been spent, and failures
     * are swallowed for the same reason the Act counter swallows them: metering
     * must never fail work the user has already paid a request for. The cost of
     * that choice is an undercount of one if the process dies between the model
     * call and this write — which is the right side to err on, since the
     * alternative is charging someone for a save they never got.
     */
    public void countSave(UUID userId) {
        increment(userId, "saves_used", monthStart());
    }

    /** Counts one Act conversion against this week's allowance. */
    public void countAct(UUID userId) {
        increment(userId, "acts_used", weekStart());
    }

    @Transactional(readOnly = true)
    public UsageSummary summary(UUID userId) {
        boolean unlimited = !props.enforceFreeCaps() || entitlements.isPro(userId);
        return new UsageSummary(
                read(userId, "saves_used", monthStart()),
                unlimited ? -1 : props.freeSavesPerMonth(),
                read(userId, "acts_used", weekStart()),
                unlimited ? -1 : props.freeActsPerWeek());
    }

    private int read(UUID userId, String column, LocalDate periodStart) {
        // The column name is one of two compile-time constants from this class,
        // never anything that came off the wire — there is nothing here for a
        // parameter to bind, and no path by which a caller could reach it.
        return jdbc.sql("select " + column + " from usage_counters where user_id = ? and period_start = ?")
                .param(userId)
                .param(periodStart)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    @Transactional
    void increment(UUID userId, String column, LocalDate periodStart) {
        try {
            jdbc.sql("""
                            insert into usage_counters (user_id, period_start, %s)
                            values (?, ?, 1)
                            on conflict (user_id, period_start)
                            do update set %s = usage_counters.%s + 1
                            """.formatted(column, column, column))
                    .param(userId)
                    .param(periodStart)
                    .update();
        } catch (RuntimeException e) {
            log.warn("Could not record {} usage for {}: {}", column, userId, e.toString());
        }
    }

    /** First of the current calendar month, UTC. */
    static LocalDate monthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }

    /**
     * The Monday of the current ISO week, UTC — so "one per week" has one
     * boundary for everybody rather than a rolling seven days that resets at a
     * different moment for each user.
     */
    static LocalDate weekStart() {
        return LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
