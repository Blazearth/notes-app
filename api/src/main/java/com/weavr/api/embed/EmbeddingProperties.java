package com.weavr.api.embed;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@code gemini-embedding-001}.
 *
 * @param model      the embedding model. Its pool is separate from the
 *                   generation models', so embedding never spends a save's RPD
 * @param dimensions <b>Must stay 1536.</b> The model defaults to 3072 and
 *                   {@code saves.embedding} is {@code vector(1536)}, baked into
 *                   V1. Changing this is a migration <em>plus</em> a full
 *                   re-embedding backfill — a one-way door, not a setting
 * @param timeout    per-request HTTP timeout
 * @param maxChars   upper bound on the profile text sent per save. The profile
 *                   is already a compact summary, so this only guards against a
 *                   runaway {@code structured_data}
 * @param batchSize  how many saves one backfill pass embeds
 */
@ConfigurationProperties(prefix = "weavr.embedding")
public record EmbeddingProperties(
        String model,
        int dimensions,
        Duration timeout,
        int maxChars,
        int batchSize
) {

    /** What V1's DDL was sized for. Not a default — a constraint. */
    public static final int REQUIRED_DIMENSIONS = 1536;

    public EmbeddingProperties {
        if (model == null || model.isBlank()) model = "gemini-embedding-001";
        if (dimensions <= 0) dimensions = REQUIRED_DIMENSIONS;
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (maxChars <= 0) maxChars = 8_000;
        if (batchSize <= 0) batchSize = 25;

        if (dimensions != REQUIRED_DIMENSIONS) {
            // Fail at startup rather than at the first insert. A mismatch here
            // surfaces as a per-row "expected 1536 dimensions, not N" from
            // pgvector on a background job, which is both far from the cause
            // and easy to mistake for a data problem.
            throw new IllegalArgumentException(
                    "weavr.embedding.dimensions must be " + REQUIRED_DIMENSIONS
                            + " to match saves.embedding vector(" + REQUIRED_DIMENSIONS + "); "
                            + "changing it needs a migration and a full re-embedding backfill");
        }
    }
}
