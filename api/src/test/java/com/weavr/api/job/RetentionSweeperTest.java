package com.weavr.api.job;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retention is the difference between a pipeline that runs and a 500 MB
 * database that stops accepting writes — see {@code docs/parallel-processing.md}
 * §2.
 *
 * <p>No local Postgres exists (CLAUDE.md), so as with {@code
 * SaveItemStateServiceTest} these cover the batching contract and the *shape*
 * of the SQL rather than its behaviour against real rows. The one thing worth
 * pinning hardest is the status predicate: deleting a stage row belonging to a
 * save that has not finished costs a paid Gemini request on retry, and no test
 * that only counted rows would notice.
 */
class RetentionSweeperTest {

    private JdbcClient jdbc;
    private RetentionSweeper sweeper;
    private final List<String> executedSql = new ArrayList<>();

    private static RetentionProperties props(int batchSize) {
        return new RetentionProperties(
                true,
                Duration.ofDays(7),
                Duration.ofDays(7),
                Duration.ofDays(30),
                batchSize,
                Duration.ofMinutes(5),
                Duration.ofHours(6));
    }

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        executedSql.clear();
        sweeper = new RetentionSweeper(jdbc, props(500));
    }

    /**
     * The load-bearing assertion of this whole class.
     *
     * <p>{@code ClassifySaveHandler} reads {@code stage='extracted'} as its
     * input and {@code stage='classified'} to skip re-spending a Gemini request
     * on a retry. A save that is {@code processing}, or {@code pending} because
     * the daily budget was spent, is still going to be classified — deleting
     * its stages by age alone would destroy the input and pay for the model
     * call twice.
     */
    @Test
    void stageDeletionIsScopedToSavesThatHaveActuallyFinished() {
        stub("delete from save_stages", 0);

        sweeper.sweepStages();

        String sql = onlySqlContaining("save_stages");
        assertThat(sql).contains("s.status in ('ready', 'failed')");
        assertThat(sql).doesNotContain("pending");
        assertThat(sql).doesNotContain("processing");
    }

    /** Age is applied to the *save*, not to the stage row — a retried save rewrites its stage. */
    @Test
    void stageAgeIsMeasuredOnTheSaveNotTheStageRow() {
        stub("delete from save_stages", 0);

        sweeper.sweepStages();

        assertThat(onlySqlContaining("save_stages")).contains("s.updated_at <");
    }

    /** A partial batch means the table is drained; issuing another statement is wasted work. */
    @Test
    void aPartialBatchEndsTheSweep() {
        stub("delete from jobs", 3);

        int deleted = sweeper.sweepJobs("succeeded", Duration.ofDays(7));

        assertThat(deleted).isEqualTo(3);
        verify(jdbc, times(1)).sql(contains("delete from jobs"));
    }

    /** A full batch means there is probably more, so the loop continues. */
    @Test
    void fullBatchesKeepGoingUntilOneComesBackShort() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("delete from jobs"))).thenAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return spec;
        });
        when(spec.param(any())).thenReturn(spec);
        // Two full batches, then a short one.
        when(spec.update()).thenReturn(500, 500, 120);

        int deleted = sweeper.sweepJobs("succeeded", Duration.ofDays(7));

        assertThat(deleted).isEqualTo(1120);
        verify(jdbc, times(3)).sql(contains("delete from jobs"));
    }

    /**
     * A sweep that could never finish must not run forever holding a pool
     * connection — the next sweep resumes from wherever this one stopped.
     */
    @Test
    void theBatchCeilingBoundsASweepThatCannotKeepUp() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("delete from jobs"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(500);   // always full — never drains

        int deleted = sweeper.sweepJobs("succeeded", Duration.ofDays(7));

        assertThat(deleted).isEqualTo(RetentionSweeper.MAX_BATCHES * 500);
        verify(jdbc, times(RetentionSweeper.MAX_BATCHES)).sql(contains("delete from jobs"));
    }

    /**
     * Housekeeping must never take down the instance that is serving saves. The
     * job runner and the API share this process.
     */
    @Test
    void aFailedSweepIsSwallowedRatherThanPropagated() {
        when(jdbc.sql(any())).thenThrow(new IllegalStateException("pooler said no"));

        sweeper.sweep();   // must not throw
    }

    /** Succeeded and failed jobs have different retentions, so they are separate statements. */
    @Test
    void succeededAndFailedJobsAreSweptSeparately() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(any())).thenAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return spec;
        });
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(0);

        sweeper.sweep();

        assertThat(executedSql).hasSize(3);   // stages + succeeded + failed
        assertThat(executedSql.stream().filter(s -> s.contains("delete from jobs")).count()).isEqualTo(2);
        assertThat(executedSql.stream().filter(s -> s.contains("delete from save_stages")).count()).isEqualTo(1);
    }

    /** Batching exists to keep transactions short on the transaction pooler. */
    @Test
    void everyDeleteIsBoundedByAnExplicitLimit() {
        stub("delete from save_stages", 0);
        stub("delete from jobs", 0);

        sweeper.sweep();

        assertThat(executedSql).isNotEmpty();
        assertThat(executedSql).allSatisfy(sql -> assertThat(sql).contains("limit ?"));
    }

    /** Nothing is deleted outright — every statement narrows by ctid from a bounded subquery. */
    @Test
    void deletesNeverRunUnscoped() {
        stub("delete from save_stages", 0);
        stub("delete from jobs", 0);

        sweeper.sweep();

        assertThat(executedSql).allSatisfy(sql -> assertThat(sql).contains("where ctid in ("));
        verify(jdbc, never()).sql(contains("delete from saves"));
    }

    private void stub(String sqlFragment, int deleted) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains(sqlFragment))).thenAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return spec;
        });
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(deleted);
    }

    private String onlySqlContaining(String fragment) {
        List<String> matches = executedSql.stream().filter(s -> s.contains(fragment)).toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }
}
