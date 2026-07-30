package com.weavr.api.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * How a handler's outcome maps onto the job row. This is the part of the runner
 * that decides whether a save eventually succeeds, retries or dies, so each
 * branch is pinned separately.
 */
class JobRunnerTest {

    private static final JobProperties PROPERTIES = new JobProperties(
            true, 2, Duration.ofSeconds(2), "test",
            Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(15));

    private static JobRecord job(int attempts, int maxAttempts) {
        return new JobRecord(UUID.randomUUID(), JobType.PROCESS_SAVE,
                Map.of("saveId", UUID.randomUUID().toString()), attempts, maxAttempts, "user-1");
    }

    /** A handler whose behaviour is supplied per test. */
    private static JobHandler handler(Consumer<JobRecord> body) {
        return new JobHandler() {
            @Override
            public String type() {
                return JobType.PROCESS_SAVE;
            }

            @Override
            public void handle(JobRecord record) {
                body.accept(record);
            }
        };
    }

    private static JobRunner runnerFor(JobStore store, JobHandler handler) {
        return new JobRunner(store, PROPERTIES, List.of(handler));
    }

    @Test
    void marksSucceededWhenTheHandlerReturns() {
        JobStore store = mock(JobStore.class);
        JobRecord record = job(0, 5);

        runnerFor(store, handler(r -> {
        })).run(record);

        verify(store).succeed(record.id());
        verifyNoMoreInteractions(store);
    }

    @Test
    void retryAfterDefersWithoutSpendingAnAttempt() {
        JobStore store = mock(JobStore.class);
        JobRecord record = job(0, 5);

        runnerFor(store, handler(r -> {
            throw new RetryAfterException("daily Gemini budget spent", Duration.ofHours(6));
        })).run(record);

        verify(store).retryAfter(eq(record.id()), eq(Duration.ofHours(6)), any());
        // The distinction that matters: a quota rejection must not consume the
        // retry budget of work that was never broken.
        verify(store, never()).retry(any(), anyInt(), any());
        verify(store, never()).fail(any(), any());
    }

    @Test
    void permanentFailureSkipsRetriesAndTellsTheHandler() {
        JobStore store = mock(JobStore.class);
        JobRecord record = job(0, 5);
        var reported = new java.util.concurrent.atomic.AtomicReference<String>();

        JobHandler handler = new JobHandler() {
            @Override
            public String type() {
                return JobType.PROCESS_SAVE;
            }

            @Override
            public void handle(JobRecord r) {
                throw new PermanentJobException("save_deleted", "This save no longer exists.");
            }

            @Override
            public void onPermanentFailure(JobRecord r, String errorCode, String userMessage) {
                reported.set(errorCode + "|" + userMessage);
            }
        };

        runnerFor(store, handler).run(record);

        verify(store).fail(eq(record.id()), any());
        verify(store, never()).retry(any(), anyInt(), any());
        assertThat(reported.get()).isEqualTo("save_deleted|This save no longer exists.");
    }

    @Test
    void unknownExceptionsRetryWhileAttemptsRemain() {
        JobStore store = mock(JobStore.class);
        when(store.backoffFor(anyInt())).thenReturn(Duration.ofSeconds(30));
        JobRecord record = job(1, 5);

        runnerFor(store, handler(r -> {
            throw new IllegalStateException("yt-dlp exploded");
        })).run(record);

        // Retries carry the attempts value from *before* this run; the store
        // does the increment.
        verify(store).retry(eq(record.id()), eq(1), any());
        verify(store, never()).fail(any(), any());
    }

    @Test
    void unknownExceptionsFailOnceAttemptsAreExhausted() {
        JobStore store = mock(JobStore.class);
        JobRecord record = job(4, 5);
        var reported = new java.util.concurrent.atomic.AtomicReference<String>();

        JobHandler handler = new JobHandler() {
            @Override
            public String type() {
                return JobType.PROCESS_SAVE;
            }

            @Override
            public void handle(JobRecord r) {
                throw new RetryableJobException("still failing");
            }

            @Override
            public void onPermanentFailure(JobRecord r, String errorCode, String userMessage) {
                reported.set(errorCode);
            }
        };

        runnerFor(store, handler).run(record);

        verify(store).fail(eq(record.id()), any());
        verify(store, never()).retry(any(), anyInt(), any());
        // The save has to be told, or the feed shows "Processing" forever.
        assertThat(reported.get()).isEqualTo("job_failed");
    }

    @Test
    void failsJobsWithNoRegisteredHandler() {
        JobStore store = mock(JobStore.class);
        JobRecord record = new JobRecord(
                UUID.randomUUID(), "some_future_type", Map.of(), 0, 5, "user-1");

        new JobRunner(store, PROPERTIES, List.of(handler(r -> {
        }))).run(record);

        verify(store).fail(eq(record.id()), any());
    }

    /** Reporting a failure must not be able to mask the failure itself. */
    @Test
    void survivesAHandlerThatThrowsWhileReportingFailure() {
        JobStore store = mock(JobStore.class);
        JobRecord record = job(0, 5);

        JobHandler handler = new JobHandler() {
            @Override
            public String type() {
                return JobType.PROCESS_SAVE;
            }

            @Override
            public void handle(JobRecord r) {
                throw new PermanentJobException("bad_payload", "We couldn't read that.");
            }

            @Override
            public void onPermanentFailure(JobRecord r, String errorCode, String userMessage) {
                throw new IllegalStateException("database is down too");
            }
        };

        runnerFor(store, handler).run(record);

        verify(store).fail(eq(record.id()), any());
    }
}
