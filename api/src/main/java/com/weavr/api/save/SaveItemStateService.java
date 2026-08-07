package com.weavr.api.save;

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
 * The one mechanism behind every knowledge type's interactivity — exercise
 * ticks, checklist items, watch status + rating, reading progress. One table
 * ({@code save_item_states}), one upsert. See
 * {@code docs/next-phases.md} §4.1.
 *
 * <p>Written with {@code JdbcClient} rather than a JPA entity, the same call
 * {@link com.weavr.api.act.ShoppingListService} made: there is no interesting
 * object graph here, just an upsert keyed on a composite id.
 */
@Service
public class SaveItemStateService {

    private static final Logger log = LoggerFactory.getLogger(SaveItemStateService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final SaveService saves;
    private final ObjectMapper objectMapper;

    SaveItemStateService(JdbcClient jdbc, SaveService saves, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.saves = saves;
        this.objectMapper = objectMapper;
    }

    /**
     * Full-row upsert, never a merge into the jsonb — the same
     * replace-don't-accumulate rule the shopping list fold and the weekly
     * digest already follow. A retried request lands on the same final state
     * instead of layering partial writes on top of each other.
     *
     * <p>{@link SaveService#getForUser} is the access check — own save, or a
     * save in a Space the caller belongs to — and it throws
     * {@link com.weavr.api.common.NotFoundException} when neither holds.
     * State is always recorded under the caller's own id even on a shared
     * save: two Space members tick their own copy of the same checklist
     * independently, the same reasoning {@code save_votes} (V7) is a row per
     * (save, user) rather than a tally.
     *
     * @return every item state the caller has recorded for this save, so the
     *         response can embed the full set without a second round trip
     */
    @Transactional
    public Map<String, Map<String, Object>> setState(UUID userId, UUID saveId, String itemPath,
                                                       Map<String, Object> state) {
        saves.getForUser(userId, saveId);

        jdbc.sql("""
                        insert into save_item_states (save_id, user_id, item_path, state)
                        values (?, ?, ?, ?::jsonb)
                        on conflict (save_id, user_id, item_path)
                        do update set state = excluded.state, updated_at = now()
                        """)
                .param(saveId)
                .param(userId)
                .param(itemPath)
                .param(serialise(state))
                .update();

        log.info("Save {} item '{}' state set by user {}", saveId, itemPath, userId);
        return statesFor(userId, saveId);
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> statesFor(UUID userId, UUID saveId) {
        return statesForSaves(userId, List.of(saveId)).getOrDefault(saveId, Map.of());
    }

    /**
     * Batched for the feed: one query for a whole page of saves rather than
     * one per row — the same N-could-grow-with-page-size reasoning as
     * everywhere else in this codebase that reads a list.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Map<String, Object>>> statesForSaves(UUID userId, List<UUID> saveIds) {
        if (saveIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, Map<String, Object>>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        select save_id, item_path, state::text as state
                        from save_item_states
                        where user_id = ? and save_id = any(?::uuid[])
                        """)
                .param(userId)
                .param(saveIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> new Object[]{
                        rs.getObject("save_id", UUID.class),
                        rs.getString("item_path"),
                        rs.getString("state")
                })
                .list()
                .forEach(row -> {
                    UUID saveId = (UUID) row[0];
                    String path = (String) row[1];
                    Map<String, Object> state = deserialise((String) row[2]);
                    result.computeIfAbsent(saveId, k -> new LinkedHashMap<>()).put(path, state);
                });
        return result;
    }

    private String serialise(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise item state", e);
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
