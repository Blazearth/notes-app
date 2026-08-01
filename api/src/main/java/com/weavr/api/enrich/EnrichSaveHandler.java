package com.weavr.api.enrich;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.save.SaveStageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline stage 3: fill gaps in {@code structured_data} from external APIs.
 *
 * <h2>Where this sits, and why it moved</h2>
 * <pre>
 *   classify_save → enrich_save → embed_save
 * </pre>
 * <p>Enrichment used to be absent and {@code classify_save} enqueued
 * {@code embed_save} directly. It has to go <em>between</em> them, because the
 * embedding is built from {@code structured_data} (CA#8) — embed first and the
 * vector never contains the director, the address or the coordinates, which are
 * exactly the high-signal terms a semantic search should be matching on. Running
 * it after would need a second embedding job, and the existing one is deduped by
 * a key derived from the save id, so the second would silently never run.
 *
 * <p>Consequently this handler enqueues {@code embed_save} on <em>every</em>
 * path, including the ones where it enriched nothing. A save whose enricher is
 * unconfigured, or whose external source was down, must still end up
 * searchable; making the last link in the chain conditional would mean an
 * unset API key quietly disabled semantic search.
 *
 * <h2>Additive only</h2>
 * <p>Enrichment fills gaps — a missing field, or one holding the registry's
 * {@code [unclear]} sentinel — and never overwrites a value the user's own
 * content produced. If a caption says the pasta takes 20 minutes and a database
 * says 25, the caption is what the user saw and chose to save. This also keeps
 * the failure bounded: the worst a wrong match can do is add a field, never
 * corrupt one.
 */
@Component
public class EnrichSaveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EnrichSaveHandler.class);

    public static final String STAGE_ENRICHED = "enriched";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Map<String, Enricher> enrichers;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SaveStageWriter stages;
    private final JobQueue jobQueue;

    EnrichSaveHandler(List<Enricher> enrichers, JdbcClient jdbc, ObjectMapper objectMapper,
                      SaveStageWriter stages, JobQueue jobQueue) {
        // Keyed by knowledge type, so a new type is a new @Component and
        // nothing else — the same shape as KnowledgeTypeRegistry.
        this.enrichers = enrichers.stream()
                .collect(Collectors.toMap(Enricher::knowledgeType, Function.identity()));
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.stages = stages;
        this.jobQueue = jobQueue;
    }

    @Override
    public String type() {
        return JobType.ENRICH_SAVE;
    }

    private record SaveRow(UUID userId, String knowledgeType, String structuredData) {
    }

    @Override
    public void handle(JobRecord job) {
        UUID saveId = job.uuidParam("saveId");

        SaveRow save = jdbc.sql("""
                        select user_id, knowledge_type, structured_data::text as structured_data
                        from saves
                        where id = ? and status = 'ready'
                        """)
                .param(saveId)
                .query((rs, row) -> new SaveRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("knowledge_type"),
                        rs.getString("structured_data")))
                .optional()
                .orElse(null);

        if (save == null) {
            // Not ready, or deleted while queued. Not an error and not worth a
            // retry — and nothing downstream to enqueue either.
            log.debug("Save {} is not ready to enrich; skipping", saveId);
            return;
        }

        try {
            enrichInto(saveId, save);
        } catch (RuntimeException e) {
            // Deliberately swallowed. The save is already complete; an
            // enrichment failure must not fail the job, because a failed job
            // would never reach the enqueue below and the save would silently
            // lose its embedding.
            log.warn("Enrichment failed for save {}: {}", saveId, e.toString());
        }

        enqueueEmbed(saveId, save.userId());
    }

    private void enrichInto(UUID saveId, SaveRow save) {
        Enricher enricher = enrichers.get(save.knowledgeType());
        if (enricher == null) {
            log.debug("No enricher for knowledge type '{}'", save.knowledgeType());
            return;
        }

        Map<String, Object> current = parse(save.structuredData());
        Optional<Map<String, Object>> found = enricher.enrich(current);
        if (found.isEmpty()) {
            return;
        }

        Map<String, Object> additions = gapsOnly(current, found.get());
        if (additions.isEmpty()) {
            log.debug("Save {}: enricher found nothing the save was missing", saveId);
            return;
        }

        merge(saveId, additions);

        // Provenance goes to save_stages, not into structured_data: the app
        // renders unknown structured fields generically, and the search vector
        // indexes every value in it — a "source: tmdb" line would appear on the
        // card and be searchable, which is not what either is for.
        stages.record(saveId, STAGE_ENRICHED, Map.of(
                "source", enricher.getClass().getSimpleName(),
                "knowledgeType", save.knowledgeType(),
                "fields", List.copyOf(additions.keySet())));

        log.info("Save {} enriched with {} from {}", saveId, additions.keySet(), enricher.knowledgeType());
    }

    /**
     * Keeps only what the save was actually missing. See the class javadoc:
     * enrichment is additive, and a field the extraction already filled belongs
     * to the user's content, not to a database.
     */
    static Map<String, Object> gapsOnly(Map<String, Object> current, Map<String, Object> found) {
        Map<String, Object> additions = new LinkedHashMap<>();
        found.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            // An empty array counts as a gap: the extractor emits [] for "the
            // content listed none", which for `genre` is absence rather than a
            // statement that the film has no genres.
            boolean emptyList = current.get(key) instanceof List<?> list && list.isEmpty();
            if (Fields.isGap(current, key) || emptyList) {
                additions.put(key, value);
            }
        });
        return additions;
    }

    /**
     * {@code ||} rather than a read-modify-write of the whole document: the
     * merge happens inside Postgres, so a concurrent update to another key
     * cannot be lost between the read and the write.
     */
    @Transactional
    void merge(UUID saveId, Map<String, Object> additions) {
        jdbc.sql("""
                        update saves
                        set structured_data = structured_data || ?::jsonb,
                            updated_at      = now()
                        where id = ?
                        """)
                .param(objectMapper.writeValueAsString(additions))
                .param(saveId)
                .update();
    }

    /**
     * Its own transaction, for the reason every other handler's enqueue is:
     * {@link JobQueue#enqueueForUser} demands an active transaction and the
     * runner calls {@code handle()} outside one.
     */
    @Transactional
    void enqueueEmbed(UUID saveId, UUID userId) {
        try {
            jobQueue.enqueueForUser(
                    JobType.EMBED_SAVE,
                    Map.of("saveId", saveId.toString()),
                    JobType.EMBED_SAVE + ":" + saveId,
                    userId);
        } catch (RuntimeException e) {
            log.warn("Could not enqueue embedding for save {}: {}", saveId, e.toString());
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
