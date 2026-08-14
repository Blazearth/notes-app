package com.weavr.api.embed;

import java.util.List;
import java.util.Map;

import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Embeds text with {@code gemini-embedding-001}.
 *
 * <p><b>No {@link com.weavr.api.gemini.BudgetApproved} token, deliberately.</b>
 * Every other Gemini call in this codebase is structurally prevented from
 * running without passing the budget guard first — but embeddings draw on a
 * separate, far larger rate-limit pool from the generation models, so counting
 * them against the 500-per-day save budget would throttle saves for no reason.
 * Re-embedding backfills are not a budget event. That is the one exception, and
 * it is worth stating loudly precisely because the rule it breaks is otherwise
 * absolute.
 *
 * <p><b>{@code outputDimensionality} is sent explicitly and is not optional.</b>
 * The model returns <em>3072</em> dimensions by default; {@code saves.embedding}
 * is {@code vector(1536)}, baked into V1's DDL. Omitting the parameter produces
 * vectors Postgres rejects one row at a time, far from the cause.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestClient http;
    private final EmbeddingProperties props;
    private final GeminiProperties gemini;
    private final ObjectMapper objectMapper;

    // Injected builder, not RestClient.builder() inline, so a test can bind
    // MockRestServiceServer to it rather than mocking the HTTP layer away —
    // the pattern the Gemini UTF-8 bug forced on every client here.
    EmbeddingClient(EmbeddingProperties props, GeminiProperties gemini,
                    ObjectMapper objectMapper, @Qualifier("embedding") RestClient.Builder restClientBuilder) {
        this.props = props;
        this.gemini = gemini;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * @param text the profile to embed — see {@link EmbeddingProfile}
     * @return exactly {@link EmbeddingProperties#REQUIRED_DIMENSIONS} floats
     * @throws PermanentJobException if the response is the wrong shape or width;
     *                               retrying an identical request would only
     *                               reproduce it
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new PermanentJobException("empty_embedding_input",
                    "There was nothing to index for this save.");
        }

        String url = BASE + props.model() + ":embedContent?key=" + gemini.apiKey();

        Map<String, Object> body = Map.of(
                "model", "models/" + props.model(),
                "content", Map.of("parts", List.of(Map.of("text", text))),
                // The task type genuinely changes the vector: the same text
                // embedded for storage and for querying lands in different
                // places, and mixing them degrades retrieval quality.
                "taskType", "RETRIEVAL_DOCUMENT",
                "outputDimensionality", props.dimensions());

        try {
            // Raw bytes, not String — same reason as GeminiClient.classify:
            // the response carries no charset and Spring would guess.
            byte[] raw = http.post()
                    .uri(url)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(byte[].class);

            JsonNode values = objectMapper.readTree(raw).path("embedding").path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw new PermanentJobException("bad_embedding_response",
                        "The indexing service returned an unexpected response.");
            }
            if (values.size() != props.dimensions()) {
                // Almost always means outputDimensionality was dropped from the
                // request and the model fell back to its 3072 default.
                throw new PermanentJobException("embedding_dimension_mismatch",
                        "The indexing service returned the wrong vector size (" + values.size()
                                + " instead of " + props.dimensions() + ").");
            }

            return normalise(toVector(values));

        } catch (PermanentJobException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RetryableJobException("Embedding rate limit hit (429), will retry.", e);
            }
            throw new RetryableJobException(
                    "Embedding returned an unexpected client error: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableJobException(
                    "The embedding service is temporarily unavailable (" + e.getStatusCode() + ").", e);
        } catch (Exception e) {
            log.debug("Embedding call failed: {}", e.toString());
            throw new RetryableJobException("An error occurred while embedding: " + e.getMessage(), e);
        }
    }

    private static float[] toVector(JsonNode values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }

    /**
     * Scales the vector to unit length.
     *
     * <p><b>Not redundant, and measured rather than assumed.</b>
     * {@code gemini-embedding-001} is a Matryoshka model: asking for 1536
     * dimensions returns a <em>truncated</em> 3072-dimension vector — the
     * leading values are byte-identical — and truncation destroys the
     * normalisation. Against the real API: the full 3072 vector comes back at
     * L2 norm 1.000000, the 1536 truncation at <b>0.691743</b>.
     *
     * <p>Cosine distance ({@code <=>}, which is what V1's HNSW index uses)
     * normalises internally, so ranking is correct either way today. This
     * exists for two other reasons:
     *
     * <ul>
     *   <li>V1's own DDL comment states the column holds "normalised embedding
     *       vectors". It did not. Making that true is cheaper than discovering
     *       later that it was never enforced.</li>
     *   <li>{@code <->} (L2) and {@code <#>} (inner product) are <em>not</em>
     *       scale-invariant. Switching operator — an innocuous-looking
     *       one-character change — would silently rank by vector magnitude
     *       instead of by similarity, with no error anywhere.</li>
     * </ul>
     */
    static float[] normalise(float[] vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        double norm = Math.sqrt(sumSquares);
        // A genuinely zero vector cannot be normalised; returning it unchanged
        // is better than emitting NaNs that pgvector would reject per row.
        if (norm == 0 || !Double.isFinite(norm)) {
            return vector;
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / norm);
        }
        return unit;
    }

    /**
     * Embeds a user's search query.
     *
     * <p>{@code RETRIEVAL_QUERY} rather than {@code RETRIEVAL_DOCUMENT}: the
     * model places questions and the documents that answer them differently on
     * purpose, and using the document task type for a query is a quiet,
     * measurable loss of retrieval quality rather than an error.
     */
    public float[] embedQuery(String query) {
        String url = BASE + props.model() + ":embedContent?key=" + gemini.apiKey();
        Map<String, Object> body = Map.of(
                "model", "models/" + props.model(),
                "content", Map.of("parts", List.of(Map.of("text", query))),
                "taskType", "RETRIEVAL_QUERY",
                "outputDimensionality", props.dimensions());

        try {
            byte[] raw = http.post().uri(url)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve().body(byte[].class);
            JsonNode values = objectMapper.readTree(raw).path("embedding").path("values");
            if (!values.isArray() || values.size() != props.dimensions()) {
                return null;
            }
            return normalise(toVector(values));
        } catch (Exception e) {
            // A search must not 500 because the embedding service is having a
            // moment. The caller degrades to full-text-only ranking, which is
            // a worse search rather than no search.
            log.warn("Could not embed search query, falling back to full-text only: {}", e.toString());
            return null;
        }
    }
}
