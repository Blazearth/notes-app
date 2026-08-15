package com.weavr.api.gemini;

import java.time.Duration;

import com.weavr.api.job.RetryAfterException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The primary-then-fallback-then-park decision tree that stands between a
 * queued save and Gemini. {@link com.weavr.api.job.RetryAfterException} here
 * is what stops a quota rejection from burning a save's retry budget, so the
 * "both exhausted" branch matters as much as the happy path.
 */
class GeminiBudgetServiceTest {

    private static final GeminiProperties PROPS = new GeminiProperties(
            "test-key", "gemini-2.5-flash-lite", "gemini-2.5-flash",
            500, 20, 1000, 1000, Duration.ofSeconds(1), 0.8, Duration.ofSeconds(30));

    /**
     * Stubs the insert-then-conditional-update pair that backs
     * {@code tryIncrement}. {@code updateResults} are consumed in call order,
     * one per {@code tryIncrement} invocation — this lets a single stub
     * represent "primary exhausted, fallback has room" as {@code 0, 1}.
     */
    private static JdbcClient stubbedJdbc(int... updateResults) {
        JdbcClient jdbc = mock(JdbcClient.class);

        JdbcClient.StatementSpec insertSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into ai_budget_days"))).thenReturn(insertSpec);
        when(insertSpec.param(any())).thenReturn(insertSpec);
        when(insertSpec.update()).thenReturn(0);

        JdbcClient.StatementSpec updateSpec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("update ai_budget_days"))).thenReturn(updateSpec);
        when(updateSpec.param(any())).thenReturn(updateSpec);
        Integer[] boxed = new Integer[updateResults.length];
        for (int i = 0; i < updateResults.length; i++) {
            boxed[i] = updateResults[i];
        }
        when(updateSpec.update()).thenReturn(boxed[0],
                java.util.Arrays.copyOfRange(boxed, 1, boxed.length));

        return jdbc;
    }

    @Test
    void approvesThePrimaryModelWhenUnderBudget() {
        GeminiBudgetService service = new GeminiBudgetService(stubbedJdbc(1), PROPS, new ModelRateLimiter());

        BudgetApproved approved = service.acquire();

        assertThat(approved.model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(approved.rpd()).isEqualTo(500);
    }

    @Test
    void fallsBackToTheSecondaryModelWhenPrimaryIsExhausted() {
        JdbcClient jdbc = stubbedJdbc(0, 1);
        GeminiBudgetService service = new GeminiBudgetService(jdbc, PROPS, new ModelRateLimiter());

        BudgetApproved approved = service.acquire();

        assertThat(approved.model()).isEqualTo("gemini-2.5-flash");
        assertThat(approved.rpd()).isEqualTo(20);
    }

    @Test
    void parksTheSaveWhenBothPoolsAreExhausted() {
        JdbcClient jdbc = stubbedJdbc(0, 0);
        GeminiBudgetService service = new GeminiBudgetService(jdbc, PROPS, new ModelRateLimiter());

        assertThatThrownBy(service::acquire)
                .isInstanceOf(RetryAfterException.class)
                .satisfies(e -> {
                    Duration delay = ((RetryAfterException) e).delay();
                    // Somewhere between "now" and tomorrow midnight Pacific plus the
                    // 5-minute buffer — never negative, never more than ~24h5m.
                    assertThat(delay).isPositive();
                    assertThat(delay).isLessThan(Duration.ofHours(24).plusMinutes(10));
                });
    }

    @Test
    void acquireForApprovesASpecificModelWhenUnderBudget() {
        JdbcClient jdbc = stubbedJdbc(1);
        GeminiBudgetService service = new GeminiBudgetService(jdbc, PROPS, new ModelRateLimiter());

        BudgetApproved approved = service.acquireFor("gemini-2.5-flash", 20);

        assertThat(approved.model()).isEqualTo("gemini-2.5-flash");
        assertThat(approved.rpd()).isEqualTo(20);
    }

    @Test
    void acquireForThrowsWhenThatModelsPoolIsExhausted() {
        JdbcClient jdbc = stubbedJdbc(0);
        GeminiBudgetService service = new GeminiBudgetService(jdbc, PROPS, new ModelRateLimiter());

        assertThatThrownBy(() -> service.acquireFor("gemini-2.5-flash", 20))
                .isInstanceOf(RetryAfterException.class);
    }

    @Test
    void everyAttemptFirstEnsuresARowExistsForTodayBeforeIncrementing() {
        JdbcClient jdbc = stubbedJdbc(1);
        GeminiBudgetService service = new GeminiBudgetService(jdbc, PROPS, new ModelRateLimiter());

        service.acquire();

        verify(jdbc).sql(contains("insert into ai_budget_days"));
        verify(jdbc).sql(contains("update ai_budget_days"));
    }
}
