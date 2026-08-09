package com.weavr.api.sync;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcClient} mocked, as everywhere else in this codebase that has no
 * local Postgres to talk to (see CLAUDE.md) — so this covers the two behaviours
 * that are Java rather than SQL: one row per audience member, and a failure that
 * cannot take the delete down with it.
 */
class TombstoneServiceTest {

    private JdbcClient jdbc;
    private TombstoneService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        service = new TombstoneService(jdbc);
    }

    private JdbcClient.StatementSpec stubInsert() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into tombstones"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        return spec;
    }

    @Test
    void oneRowPerAudienceMember() {
        JdbcClient.StatementSpec insert = stubInsert();

        service.recordFor(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                TombstoneService.SPACE, UUID.randomUUID().toString());

        verify(insert, times(3)).update();
    }

    /** The ordinary case for a comment on a private save: nobody else to tell. */
    @Test
    void anEmptyAudienceWritesNothingAtAll() {
        service.recordFor(List.of(), TombstoneService.COMMENT, "whatever");
        verifyNoInteractions(jdbc);
    }

    /**
     * A missing tombstone costs one client a stale row until its next full pull.
     * Failing the delete would cost the user the action they asked for — so the
     * write is swallowed, exactly like {@code SpaceService.recordActivity}.
     */
    @Test
    void aFailedWriteNeverPropagatesToTheCaller() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into tombstones"))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenThrow(new IllegalStateException("connection gone"));

        assertThatCode(() -> service.record(UUID.randomUUID(), TombstoneService.SPACE, "x"))
                .doesNotThrowAnyException();
    }
}
