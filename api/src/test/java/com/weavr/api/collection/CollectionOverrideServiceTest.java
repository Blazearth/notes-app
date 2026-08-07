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
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code EntityStateServiceTest}'s style: {@link JdbcClient} is
 * mocked (no local Postgres — see CLAUDE.md), so these cover the SQL shape
 * and {@link CollectionOverrideService#loadFor}'s chain-resolution logic,
 * which is plain Java and worth pinning independent of the database.
 */
class CollectionOverrideServiceTest {

    private JdbcClient jdbc;
    private CollectionOverrideService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        service = new CollectionOverrideService(jdbc, JsonMapper.builder().build());
    }

    /** {@code {overrideType, subjectKey, payloadJson}} — mirrors the production row shape (see {@code CollectionOverrideService.loadFor}). */
    private static Object[] row(String type, String subjectKey, String payloadJson) {
        return new Object[]{type, subjectKey, payloadJson};
    }

    @SuppressWarnings("unchecked")
    private void stubLoad(List<Object[]> rows) {
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("from collection_overrides"))).thenReturn(select);
        when(select.param(any())).thenReturn(select);
        JdbcClient.MappedQuerySpec<Object[]> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
    }

    @Test
    void mergeUpsertsAFromToIntoRow() {
        JdbcClient.StatementSpec upsert = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into collection_overrides"))).thenReturn(upsert);
        when(upsert.param(any())).thenReturn(upsert);
        when(upsert.update()).thenReturn(1);

        service.merge(UUID.randomUUID(), "screen:shingeki no kyojin", "tmdb:1429");

        verify(upsert).update();
    }

    @Test
    void mergingAnEntityIntoItselfIsRejected() {
        UUID userId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.merge(userId, "screen:dune", "screen:dune"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unmergeDeletesTheRow() {
        JdbcClient.StatementSpec delete = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("delete from collection_overrides"))).thenReturn(delete);
        when(delete.param(any())).thenReturn(delete);
        when(delete.update()).thenReturn(1);

        service.unmerge(UUID.randomUUID(), "screen:shingeki no kyojin");

        verify(delete).update();
    }

    @Test
    void loadForBucketsRowsByOverrideType() {
        stubLoad(List.of(
                row("entity_merge", "screen:shingeki no kyojin", "{\"into\":\"tmdb:1429\"}"),
                row("entity_rename", "tmdb:1429", "{\"name\":\"AoT\"}"),
                row("collection_rename", "recommendation_list", "{\"name\":\"My Watchlist\"}")));

        CollectionOverrides overrides = service.loadFor(UUID.randomUUID());

        assertThat(overrides.mergeRedirects()).containsEntry("screen:shingeki no kyojin", "tmdb:1429");
        assertThat(overrides.entityNames()).containsEntry("tmdb:1429", "AoT");
        assertThat(overrides.collectionNames()).containsEntry("recommendation_list", "My Watchlist");
    }

    /**
     * A into B, then later B into C: the caller wants A to resolve straight to
     * C, not to stop at the intermediate B — a two-hop chain must read the
     * same as if the user had merged A directly into C.
     */
    @Test
    void chainedMergesResolveToTheFinalTarget() {
        stubLoad(List.of(
                row("entity_merge", "screen:a", "{\"into\":\"screen:b\"}"),
                row("entity_merge", "screen:b", "{\"into\":\"screen:c\"}")));

        CollectionOverrides overrides = service.loadFor(UUID.randomUUID());

        assertThat(overrides.mergeRedirects()).containsEntry("screen:a", "screen:c");
        assertThat(overrides.mergeRedirects()).containsEntry("screen:b", "screen:c");
    }

    /** A user's own mistake (or a race) creating a merge cycle must not hang the read. */
    @Test
    void aMergeCycleDoesNotLoopForever() {
        stubLoad(List.of(
                row("entity_merge", "screen:a", "{\"into\":\"screen:b\"}"),
                row("entity_merge", "screen:b", "{\"into\":\"screen:a\"}")));

        CollectionOverrides overrides = service.loadFor(UUID.randomUUID());

        // Terminates and produces *some* deterministic key rather than hanging —
        // the exact value is an artifact of the cycle, not a contract.
        assertThat(overrides.mergeRedirects()).containsKeys("screen:a", "screen:b");
    }

    @Test
    void unknownOverrideTypeIsIgnoredRatherThanFailingTheWholeLoad() {
        stubLoad(List.of(
                row("some_future_type", "x", "{}"),
                row("entity_rename", "tmdb:1429", "{\"name\":\"AoT\"}")));

        CollectionOverrides overrides = service.loadFor(UUID.randomUUID());

        assertThat(overrides.entityNames()).containsEntry("tmdb:1429", "AoT");
    }

    @Test
    void resolveFallsBackToTheRawKeyWhenThereIsNoOverride() {
        CollectionOverrides overrides = new CollectionOverrides(Map.of("a", "b"), Map.of(), Map.of());
        assertThat(overrides.resolve("a")).isEqualTo("b");
        assertThat(overrides.resolve("c")).isEqualTo("c");
    }
}
