package com.weavr.api.save;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The mechanism behind every knowledge type's object behavior — see
 * {@code docs/next-phases.md} §4.1.
 *
 * <p>{@link SaveService} is mocked, so these tests cover the access-check
 * delegation and the shape of the SQL sent to {@link JdbcClient}. Like
 * {@code ShoppingListService}, the write logic itself has no database to run
 * against in this test suite (no local Postgres — see CLAUDE.md); the
 * upsert's actual behaviour is exercised by hitting the live deploy, not a
 * mocked chain.
 */
class SaveItemStateServiceTest {

    private JdbcClient jdbc;
    private SaveService saves;
    private SaveItemStateService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        saves = mock(SaveService.class);
        service = new SaveItemStateService(jdbc, saves, JsonMapper.builder().build());
    }

    /**
     * {@link SaveItemStateService} is not itself the access-control boundary —
     * {@link SaveService#getForUser} is, same as every other save mutation —
     * so a caller with no access must never reach the database.
     */
    @Test
    void settingStateOnASaveTheCallerCannotReachNeverTouchesTheDatabase() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        when(saves.getForUser(userId, saveId)).thenThrow(new NotFoundException("Save not found"));

        assertThatThrownBy(() -> service.setState(userId, saveId, "items[0]", Map.of("done", true)))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void settingStateUpsertsThenReturnsEveryStateForTheSave() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        when(saves.getForUser(userId, saveId)).thenReturn(
                Save.accepted(userId, SourceType.URL, "https://example.com/reel", null, null, null));

        JdbcClient.StatementSpec upsert = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into save_item_states"))).thenReturn(upsert);
        when(upsert.param(any())).thenReturn(upsert);
        when(upsert.update()).thenReturn(1);

        stubSelect(List.<Object[]>of(new Object[]{saveId, "items[0]", "{\"done\":true}"}));

        Map<String, Map<String, Object>> result =
                service.setState(userId, saveId, "items[0]", Map.of("done", true));

        verify(upsert).update();
        assertThat(result).containsOnlyKeys("items[0]");
        assertThat(result.get("items[0]")).containsEntry("done", true);
    }

    /** A malformed row must not sink the whole read — the rest of the save's state still matters. */
    @Test
    void aRowThatFailsToParseIsSkippedRatherThanFailingTheWholeRead() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();

        stubSelect(List.<Object[]>of(
                new Object[]{saveId, "items[0]", "not json"},
                new Object[]{saveId, "items[1]", "{\"done\":true}"}));

        Map<String, Map<String, Object>> result = service.statesFor(userId, saveId);

        assertThat(result.get("items[0]")).isEmpty();
        assertThat(result.get("items[1]")).containsEntry("done", true);
    }

    @Test
    void statesForSavesReturnsAnEmptyMapWithoutQueryingForNoIds() {
        assertThat(service.statesForSaves(UUID.randomUUID(), List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    private void stubSelect(List<Object[]> rows) {
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("from save_item_states"))).thenReturn(select);
        when(select.param(any())).thenReturn(select);
        JdbcClient.MappedQuerySpec<Object[]> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
    }
}
