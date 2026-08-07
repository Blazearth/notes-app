package com.weavr.api.collection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code collection_overrides} — K4's manual-merge, rename and collection-
 * rename curation. {@code JdbcClient}, not JPA, the same call {@code
 * EntityStateService} and {@code SaveItemStateService} made: no interesting
 * object graph, just keyed upserts. See {@code V14__collection_overrides.sql}
 * for the three {@code override_type} values and why pin isn't a fourth.
 */
@Service
public class CollectionOverrideService {

    private static final Logger log = LoggerFactory.getLogger(CollectionOverrideService.class);

    private static final String MERGE = "entity_merge";
    private static final String ENTITY_RENAME = "entity_rename";
    private static final String COLLECTION_RENAME = "collection_rename";

    /** Chain-chase guard for a merge cycle a user's own mistakes (or a race) could otherwise loop on forever. */
    private static final int MAX_CHAIN = 32;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    CollectionOverrideService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code fromKey} stops appearing as its own entity and every one of its
     * sources attaches to {@code intoKey} instead — the alias fix for
     * "Shingeki no Kyojin" vs. "Attack on Titan", applied by the user rather
     * than guessed at by string similarity (out of scope per this doc's own
     * "measured-first" rule).
     */
    @Transactional
    public void merge(UUID userId, String fromKey, String intoKey) {
        if (fromKey.equals(intoKey)) {
            throw new IllegalArgumentException("cannot merge an entity into itself");
        }
        upsert(userId, MERGE, fromKey, Map.of("into", intoKey));
        log.info("Entity '{}' manually merged into '{}' by user {}", fromKey, intoKey, userId);
    }

    /** Undoes a single merge — a delete, not a fourth override type. */
    @Transactional
    public void unmerge(UUID userId, String fromKey) {
        delete(userId, MERGE, fromKey);
        log.info("Merge of entity '{}' undone by user {}", fromKey, userId);
    }

    @Transactional
    public void renameEntity(UUID userId, String entityKey, String name) {
        upsert(userId, ENTITY_RENAME, entityKey, Map.of("name", name));
    }

    @Transactional
    public void renameCollection(UUID userId, String collectionId, String name) {
        upsert(userId, COLLECTION_RENAME, collectionId, Map.of("name", name));
    }

    /**
     * Every override row for this user, bucketed by type — one query, since
     * building the merge-redirect map needs every merge row regardless of
     * which entities the caller is about to render, the same reason {@code
     * EntityStateService.statesFor} batches rather than looking up one key
     * at a time.
     */
    @Transactional(readOnly = true)
    public CollectionOverrides loadFor(UUID userId) {
        // Object[] rows, not a local record — the same choice EntityStateService
        // makes and for the same reason: a mocked JdbcClient in a test returns
        // whatever list the test stubs directly, bypassing this row mapper
        // entirely, and a method-local record type would be a different class
        // from anything a test could construct, failing the cast at read time.
        List<Object[]> rows = jdbc.sql("""
                        select override_type, subject_key, payload::text as payload
                        from collection_overrides
                        where user_id = ?
                        """)
                .param(userId)
                .query((rs, i) -> new Object[]{
                        rs.getString("override_type"),
                        rs.getString("subject_key"),
                        rs.getString("payload")
                })
                .list();

        Map<String, String> rawMerges = new LinkedHashMap<>();
        Map<String, String> entityNames = new LinkedHashMap<>();
        Map<String, String> collectionNames = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            String subjectKey = (String) row[1];
            Map<String, Object> payload = deserialise((String) row[2]);
            switch (type) {
                case MERGE -> {
                    Object into = payload.get("into");
                    if (into instanceof String s && !s.isBlank()) rawMerges.put(subjectKey, s);
                }
                case ENTITY_RENAME -> {
                    Object name = payload.get("name");
                    if (name instanceof String s && !s.isBlank()) entityNames.put(subjectKey, s);
                }
                case COLLECTION_RENAME -> {
                    Object name = payload.get("name");
                    if (name instanceof String s && !s.isBlank()) collectionNames.put(subjectKey, s);
                }
                default -> log.warn("Unknown collection override type '{}' for user {}", type, userId);
            }
        }

        return new CollectionOverrides(resolveChains(rawMerges), entityNames, collectionNames);
    }

    /**
     * Chases each {@code from -> into} entry to its final target so a chain
     * of merges (A into B, then later B into C) reads as A -> C directly,
     * with a bounded, cycle-safe walk — a user re-merging into something
     * that eventually points back at the start must not loop forever.
     */
    private static Map<String, String> resolveChains(Map<String, String> raw) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String from : raw.keySet()) {
            String current = from;
            Set<String> seen = new LinkedHashSet<>();
            while (raw.containsKey(current) && seen.add(current) && seen.size() <= MAX_CHAIN) {
                current = raw.get(current);
            }
            resolved.put(from, current);
        }
        return resolved;
    }

    private void upsert(UUID userId, String type, String subjectKey, Map<String, Object> payload) {
        jdbc.sql("""
                        insert into collection_overrides (user_id, override_type, subject_key, payload)
                        values (?, ?, ?, ?::jsonb)
                        on conflict (user_id, override_type, subject_key)
                        do update set payload = excluded.payload, updated_at = now()
                        """)
                .param(userId)
                .param(type)
                .param(subjectKey)
                .param(serialise(payload))
                .update();
    }

    private void delete(UUID userId, String type, String subjectKey) {
        jdbc.sql("delete from collection_overrides where user_id = ? and override_type = ? and subject_key = ?")
                .param(userId)
                .param(type)
                .param(subjectKey)
                .update();
    }

    private String serialise(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise collection override payload", e);
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
