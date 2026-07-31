package com.weavr.api.embed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Binds {@link MockRestServiceServer} to the injected builder, the pattern the
 * Gemini encoding bug forced on every HTTP client here.
 */
class EmbeddingClientTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final EmbeddingProperties PROPS = new EmbeddingProperties(
            "gemini-embedding-001", 1536, Duration.ofSeconds(30), 8000, 25);
    private static final GeminiProperties GEMINI = new GeminiProperties(
            "test-key", "flash-lite", "flash", 500, 20, 0.7, Duration.ofSeconds(30));

    private MockRestServiceServer server;
    private EmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EmbeddingClient(PROPS, GEMINI, MAPPER, builder);
    }

    private static byte[] response(int dimensions) {
        List<Double> values = IntStream.range(0, dimensions)
                .mapToObj(i -> i / (double) dimensions)
                .toList();
        return MAPPER.writeValueAsBytes(Map.of("embedding", Map.of("values", values)));
    }

    /**
     * The single most consequential line in this class. gemini-embedding-001
     * returns 3072 dimensions by default and saves.embedding is vector(1536),
     * baked into V1's DDL — omitting the parameter produces vectors Postgres
     * rejects one row at a time, in a background job, far from the cause.
     */
    @Test
    void sendsOutputDimensionalityExplicitly() {
        server.expect(requestTo(startsWith(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.outputDimensionality").value(1536))
                .andExpect(jsonPath("$.taskType").value("RETRIEVAL_DOCUMENT"))
                .andRespond(withSuccess(response(1536), MediaType.APPLICATION_JSON));

        assertThat(client.embed("recipe\nTitle: Tiramisu")).hasSize(1536);
    }

    /**
     * A query and the document that answers it are placed differently on
     * purpose. Using the document task type for a query is not an error — it
     * is a quiet, measurable loss of retrieval quality.
     */
    @Test
    void usesTheQueryTaskTypeWhenEmbeddingASearchQuery() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andExpect(jsonPath("$.taskType").value("RETRIEVAL_QUERY"))
                .andRespond(withSuccess(response(1536), MediaType.APPLICATION_JSON));

        assertThat(client.embedQuery("pasta recipes")).hasSize(1536);
    }

    /** Belt and braces for the same trap, from the response side. */
    @Test
    void rejectsAVectorOfTheWrongWidthRatherThanStoringIt() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withSuccess(response(3072), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("text"))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("wrong vector size");
    }

    @Test
    void treatsAMalformedResponseAsPermanentNotRetryable() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withSuccess("{\"embedding\":{}}".getBytes(), MediaType.APPLICATION_JSON));

        // Retrying an identical request would only reproduce it.
        assertThatThrownBy(() -> client.embed("text"))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void rateLimitAndServerErrorsAreRetryable() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).body("{}"));
        assertThatThrownBy(() -> client.embed("text"))
                .isInstanceOf(RetryableJobException.class);

        server.reset();
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.embed("text"))
                .isInstanceOf(RetryableJobException.class);
    }

    /**
     * gemini-embedding-001 is a Matryoshka model: a 1536-dimension request
     * returns a truncated 3072 vector, and truncation destroys normalisation.
     * Measured against the real API — full vector L2 = 1.000000, truncated
     * = 0.691743. Cosine hides it; `&lt;-&gt;` and `&lt;#&gt;` would not.
     */
    @Test
    void normalisesTheStoredVectorToUnitLength() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withSuccess(response(1536), MediaType.APPLICATION_JSON));

        assertThat(norm(client.embed("text"))).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void normalisesTheQueryVectorToo() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withSuccess(response(1536), MediaType.APPLICATION_JSON));

        assertThat(norm(client.embedQuery("pasta"))).isCloseTo(1.0, within(1e-5));
    }

    /** A zero vector has no direction; NaNs would be rejected row by row. */
    @Test
    void leavesAZeroVectorAloneRatherThanEmittingNaN() {
        float[] zeros = new float[8];

        assertThat(EmbeddingClient.normalise(zeros)).containsOnly(0f);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    @Test
    void refusesToEmbedNothing() {
        assertThatThrownBy(() -> client.embed("  "))
                .isInstanceOf(PermanentJobException.class);
    }

    /**
     * A search must not 500 because the embedding service is having a moment —
     * the caller degrades to full-text-only ranking.
     */
    @Test
    void returnsNullQueryVectorInsteadOfThrowingSoSearchCanDegrade() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/")))
                .andRespond(withServerError());

        assertThat(client.embedQuery("pasta")).isNull();
    }
}
