package com.weavr.api.embed;

import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline stage 3: embed the classified save so it can be found by meaning
 * rather than by wording.
 *
 * <pre>
 *   process_save  → save_stages(stage='extracted')
 *   classify_save → structured_data, status = ready
 *   embed_save    → saves.embedding
 * </pre>
 *
 * <p><b>Deliberately after {@code ready}, not before it.</b> A save is useful
 * the moment it has structured fields; being findable by similarity is an
 * enhancement on top. Chaining it before the status flip would make every save
 * wait on a second network call for no user-visible gain, and a failure would
 * hold back a save that was already complete.
 *
 * <p>The embedding column is written with {@code JdbcClient} rather than through
 * the {@code Save} entity, because pgvector has no Hibernate type — see
 * {@link PgVectors}.
 */
@Component
class EmbedSaveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EmbedSaveHandler.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final EmbeddingProperties props;
    private final ObjectMapper objectMapper;
    private final JobQueue jobQueue;

    EmbedSaveHandler(JdbcClient jdbc, EmbeddingClient embeddings,
                     EmbeddingProperties props, ObjectMapper objectMapper, JobQueue jobQueue) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.props = props;
        this.objectMapper = objectMapper;
        this.jobQueue = jobQueue;
    }

    @Override
    public String type() {
        return JobType.EMBED_SAVE;
    }

    /** What the handler needs, without dragging the whole entity through Hibernate. */
    private record SaveRow(String knowledgeType, String structuredData, String rawCaption,
                           boolean alreadyEmbedded, UUID spaceId, UUID userId) {
    }

    @Override
    public void handle(JobRecord job) {
        UUID saveId = job.uuidParam("saveId");

        SaveRow save = jdbc.sql("""
                        select knowledge_type,
                               structured_data::text as structured_data,
                               raw_caption,
                               embedding is not null as already_embedded,
                               space_id,
                               user_id
                        from saves
                        where id = ?
                        """)
                .param(saveId)
                .query((rs, row) -> new SaveRow(
                        rs.getString("knowledge_type"),
                        rs.getString("structured_data"),
                        rs.getString("raw_caption"),
                        rs.getBoolean("already_embedded"),
                        rs.getObject("space_id", UUID.class),
                        rs.getObject("user_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new PermanentJobException(
                        "save_deleted", "This save no longer exists."));

        // Idempotency, and the reason a redeploy mid-pipeline costs nothing:
        // the stale-claim reaper can re-deliver this job at any point.
        if (save.alreadyEmbedded()) {
            log.debug("Save {} is already embedded; skipping", saveId);
            return;
        }

        String profile = EmbeddingProfile.build(
                save.knowledgeType(), parse(save.structuredData()),
                save.rawCaption(), props.maxChars());

        if (profile.isBlank()) {
            // Nothing to index. Not a failure: an `unusable` save legitimately
            // has no content, and failing the job would retry it four more
            // times to reach the same conclusion.
            log.debug("Save {} has nothing worth embedding", saveId);
            return;
        }

        float[] vector = embeddings.embed(profile);
        store(saveId, vector);

        log.info("Save {} embedded ({} chars of profile, {} dims)",
                saveId, profile.length(), vector.length);

        // Only for a shared Space: duplicate detection compares a save against
        // its neighbours, and a private save has none.
        if (save.spaceId() != null) {
            enqueueDuplicateDetection(saveId, save.userId());
        }
    }

    /**
     * Its own job, and its own transaction. A failure to enqueue must not fail
     * an embedding that already succeeded — the vector is written, the save is
     * searchable, and the only thing lost is a merge suggestion.
     */
    @Transactional
    void enqueueDuplicateDetection(UUID saveId, UUID userId) {
        try {
            jobQueue.enqueueForUser(
                    JobType.DETECT_DUPLICATES,
                    Map.of("saveId", saveId.toString()),
                    JobType.DETECT_DUPLICATES + ":" + saveId,
                    userId);
        } catch (RuntimeException e) {
            log.warn("Could not enqueue duplicate detection for save {}: {}", saveId, e.toString());
        }
    }

    @Transactional
    void store(UUID saveId, float[] vector) {
        jdbc.sql("update saves set embedding = ?::vector where id = ?")
                .param(PgVectors.toLiteral(vector))
                .param(saveId)
                .update();
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Could not parse structured_data for embedding: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * A save that cannot be embedded is still a perfectly good save — it is
     * merely absent from similarity results, and full-text search still finds
     * it. Marking it {@code failed} would take a complete save away from the
     * user over a missing index entry.
     */
    @Override
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        log.warn("Giving up on embedding for job {} ({}); the save stays ready and "
                + "findable by full-text search", job.id(), errorCode);
    }
}
