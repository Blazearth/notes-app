package com.weavr.api.collection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code SaveItemStateServiceTest}'s style: {@link JdbcClient} is
 * mocked, so these tests cover the shape of the SQL sent, not the database's
 * actual behaviour — there is no local Postgres (see CLAUDE.md), so the
 * upsert and the batched read are exercised live instead, the same way
 * {@code SaveItemStateService}'s own tests are.
 */
class EntityStateServiceTest {

    private JdbcClient jdbc;
    private EntityStateService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        service = new EntityStateService(jdbc, JsonMapper.builder().build());
    }

    @Test
    void settingStateUpsertsAndReturnsWhatWasWritten() {
        UUID userId = UUID.randomUUID();

        JdbcClient.StatementSpec upsert = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into entity_states"))).thenReturn(upsert);
        when(upsert.param(any())).thenReturn(upsert);
        when(upsert.update()).thenReturn(1);

        Map<String, Object> result = service.setState(userId, "screen:blue box", Map.of("done", true, "rating", 4));

        verify(upsert).update();
        assertThat(result).containsEntry("done", true).containsEntry("rating", 4);
    }

    /**
     * Unlike {@code SaveItemStateService.setState}, there is no access check
     * to delegate to — an entity key is not a foreign key to anything
     * ownable, so every caller can write any entity key under their own id.
     */
    @Test
    void settingStateNeverConsultsAnyOtherService() {
        UUID userId = UUID.randomUUID();
        JdbcClient.StatementSpec upsert = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into entity_states"))).thenReturn(upsert);
        when(upsert.param(any())).thenReturn(upsert);
        when(upsert.update()).thenReturn(1);

        service.setState(userId, "book:pachinko", Map.of("done", true));

        // The only collaborator is jdbc — no SaveService, no access check.
        verify(upsert).update();
    }

    @Test
    void stateForReturnsEmptyWhenNeverSet() {
        stubSelect(List.<Object[]>of());
        assertThat(service.stateFor(UUID.randomUUID(), "screen:blue box")).isEmpty();
    }

    /** A malformed row must not sink the whole read. */
    @Test
    void aRowThatFailsToParseIsSkippedRatherThanFailingTheWholeRead() {
        stubSelect(List.<Object[]>of(
                new Object[]{"screen:blue box", "not json"},
                new Object[]{"book:pachinko", "{\"done\":true}"}));

        Map<String, Map<String, Object>> result = service.statesFor(UUID.randomUUID(), List.of("screen:blue box", "book:pachinko"));

        assertThat(result.get("screen:blue box")).isEmpty();
        assertThat(result.get("book:pachinko")).containsEntry("done", true);
    }

    @Test
    void statesForReturnsAnEmptyMapWithoutQueryingForNoKeys() {
        assertThat(service.statesFor(UUID.randomUUID(), List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    private void stubSelect(List<Object[]> rows) {
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("from entity_states"))).thenReturn(select);
        when(select.param(any())).thenReturn(select);
        JdbcClient.MappedQuerySpec<Object[]> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
    }
}
