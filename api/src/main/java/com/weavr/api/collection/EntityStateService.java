package com.weavr.api.collection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-(user, entity) state — watched, rating, done — the only thing K2
 * stores. See {@code docs/knowledge-collections.md} ("Stored state") for why
 * it is entity-keyed rather than save-item-keyed: watching Blue Box must
 * survive Blue Box appearing in a fourth save tomorrow, which
 * {@link com.weavr.api.save.SaveItemStateService} (tied to one save's
 * {@code items[3]}) cannot do.
 *
 * <p>{@code JdbcClient}, not JPA — the same call {@code SaveItemStateService}
 * and {@code ShoppingListService} made: no interesting object graph, just an
 * upsert keyed on a composite id.
 */
@Service
public class EntityStateService {

    private static final Logger log = LoggerFactory.getLogger(EntityStateService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    EntityStateService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Full-row upsert, never a merge into the jsonb — the same
     * replace-don't-accumulate rule {@code SaveItemStateService} and the
     * shopping list fold already follow.
     *
     * <p>No access check beyond the caller's own id: an entity key names no
     * row this user doesn't already have their own view of — it references
     * nothing by foreign key, so there is nothing to leak or orphan.
     */
    @Transactional
    public Map<String, Object> setState(UUID userId, String entityKey, Map<String, Object> state) {
        jdbc.sql("""
                        insert into entity_states (user_id, entity_key, state)
                        values (?, ?, ?::jsonb)
                        on conflict (user_id, entity_key)
                        do update set state = excluded.state, updated_at = now()
                        """)
                .param(userId)
                .param(entityKey)
                .param(serialise(state))
                .update();

        log.info("Entity '{}' state set by user {}", entityKey, userId);
        return state;
    }

    /** One entity's state, or empty if the caller has never touched it. */
    @Transactional(readOnly = true)
    public Map<String, Object> stateFor(UUID userId, String entityKey) {
        return statesFor(userId, List.of(entityKey)).getOrDefault(entityKey, Map.of());
    }

    /**
     * Batched: one query for a whole merged entity list or collection tree
     * rather than one per entity — the same N-could-grow-with-list-size
     * reasoning as {@code SaveItemStateService.statesForSaves}.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> statesFor(UUID userId, Collection<String> entityKeys) {
        if (entityKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        select entity_key, state::text as state
                        from entity_states
                        where user_id = ? and entity_key = any(?::text[])
                        """)
                .param(userId)
                .param(entityKeys.toArray(String[]::new))
                .query((rs, row) -> new Object[]{
                        rs.getString("entity_key"),
                        rs.getString("state")
                })
                .list()
                .forEach(row -> {
                    String key = (String) row[0];
                    Map<String, Object> state = deserialise((String) row[1]);
                    result.put(key, state);
                });
        return result;
    }

    private String serialise(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise entity state", e);
        }
    }

    private Map<String, Object> deserialise(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
