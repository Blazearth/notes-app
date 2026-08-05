package com.weavr.api.gemini;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.RetryableJobException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Binds {@link MockRestServiceServer} to the injected {@code RestClient.Builder}
 * so these tests exercise the real byte-level response handling instead of a
 * mocked HTTP layer.
 *
 * <p>{@link #decodesNonAsciiTextAsUtf8RegardlessOfMissingCharsetHeader()} pins
 * the fix from the encoding bug: Gemini's response carries no charset on its
 * {@code Content-Type} header, and reading it as {@code String.class} let
 * Spring fall back to a platform-dependent charset instead of UTF-8 — "café"
 * arrived as "cafÃ©". {@link GeminiClient} now reads the body as raw bytes and
 * lets Jackson do UTF-8 detection per the JSON spec (RFC 8259); this test
 * fails again if that regresses back to {@code .body(String.class)}.
 */
class GeminiClientTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final GeminiProperties PROPS = new GeminiProperties(
            "test-key", "gemini-2.5-flash-lite", "gemini-2.5-flash",
            500, 20, 0.8, Duration.ofSeconds(30));

    private MockRestServiceServer server;
    private GeminiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        client = new GeminiClient(PROPS, MAPPER, jdbc, builder);
    }

    private static byte[] geminiEnvelope(Object generatedJsonPayload, int inputTokens, int outputTokens) {
        String generatedText = MAPPER.writeValueAsString(generatedJsonPayload);
        Map<String, Object> envelope = Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", generatedText))))),
                "usageMetadata", Map.of(
                        "promptTokenCount", inputTokens,
                        "candidatesTokenCount", outputTokens)
        );
        return MAPPER.writeValueAsBytes(envelope);
    }

    @Test
    void parsesAClassificationIntoAGeminiResponse() {
        Map<String, Object> generated = Map.of(
                "knowledgeType", "recipe",
                "confidence", 0.92,
                "title", "Tiramisu",
                "ingredients", List.of("eggs", "mascarpone"));

        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiEnvelope(generated, 120, 45), MediaType.APPLICATION_JSON));

        GeminiResponse response = client.classify(UUID.randomUUID(), "3 eggs, mascarpone, sugar",
                new BudgetApproved("gemini-2.5-flash-lite", 500));

        assertThat(response.knowledgeType()).isEqualTo("recipe");
        assertThat(response.confidence()).isEqualTo(0.92);
        assertThat(response.model()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(response.inputTokens()).isEqualTo(120);
        assertThat(response.outputTokens()).isEqualTo(45);
        assertThat(response.structuredData())
                .doesNotContainKeys("knowledgeType", "confidence")
                .containsEntry("title", "Tiramisu")
                .containsEntry("ingredients", List.of("eggs", "mascarpone"));
    }

    @Test
    void decodesNonAsciiTextAsUtf8RegardlessOfMissingCharsetHeader() {
        Map<String, Object> generated = Map.of(
                "knowledgeType", "place",
                "confidence", 0.9,
                "name", "Café Gaëlle",
                "type", "café",
                "address", "[unclear]",
                "cuisine", "[unclear]",
                "priceRange", "[unclear]",
                "highlights", List.of(),
                "rating", "[unclear]");

        // No Content-Type header at all — verified against Spring's
        // StringHttpMessageConverter bytecode to be the case that falls back
        // to ISO-8859-1 (StringHttpMessageConverter.DEFAULT_CHARSET) rather
        // than UTF-8. A present-but-charset-less "application/json" header is
        // special-cased to UTF-8 by this Spring version already, so it won't
        // reproduce the bug — this is the header shape that actually does.
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).body(geminiEnvelope(generated, 10, 5)));

        GeminiResponse response = client.classify(UUID.randomUUID(), "some overlay text",
                new BudgetApproved("gemini-2.5-flash-lite", 500));

        assertThat(response.structuredData())
                .containsEntry("name", "Café Gaëlle")
                .containsEntry("type", "café");
    }

    // -----------------------------------------------------------------------
    // Tier 2 of the visual cascade: vision OCR on keyframes.
    // -----------------------------------------------------------------------

    private static byte[] visionEnvelope(String transcribed) {
        return MAPPER.writeValueAsBytes(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", transcribed))))),
                "usageMetadata", Map.of("promptTokenCount", 2200, "candidatesTokenCount", 60)));
    }

    /**
     * The frames have to arrive as base64 {@code inline_data} parts with an
     * image mime type. Sending them any other way is not a compile error and
     * not an HTTP error — Gemini simply answers about the prompt text alone,
     * so the tier would silently return nothing useful while looking healthy.
     */
    @Test
    void sendsFramesAsBase64InlineImageParts() {
        byte[] frameOne = {(byte) 0xFF, (byte) 0xD8, 1, 2};
        byte[] frameTwo = {(byte) 0xFF, (byte) 0xD8, 3, 4};

        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.contents[0].parts[0].text").exists())
                .andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type")
                        .value(MediaType.IMAGE_JPEG_VALUE))
                .andExpect(jsonPath("$.contents[0].parts[1].inline_data.data")
                        .value(Base64.getEncoder().encodeToString(frameOne)))
                .andExpect(jsonPath("$.contents[0].parts[2].inline_data.data")
                        .value(Base64.getEncoder().encodeToString(frameTwo)))
                // Transcription, not classification: forcing a JSON schema here
                // would only wrap a string in an escaping layer.
                .andExpect(jsonPath("$.generationConfig.responseSchema").doesNotExist())
                .andRespond(withSuccess(visionEnvelope("400g rigatoni"), MediaType.APPLICATION_JSON));

        String text = client.transcribeFrames(UUID.randomUUID(), List.of(frameOne, frameTwo),
                new BudgetApproved("gemini-2.5-flash", 20));

        assertThat(text).isEqualTo("400g rigatoni");
    }

    /** Same missing-charset shape as the classify path — overlay text is not all ASCII. */
    @Test
    void decodesVisionTextAsUtf8RegardlessOfMissingCharsetHeader() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .body(visionEnvelope("Crème brûlée — 200 g sucre")));

        String text = client.transcribeFrames(UUID.randomUUID(), List.of(new byte[]{1}),
                new BudgetApproved("gemini-2.5-flash", 20));

        assertThat(text).isEqualTo("Crème brûlée — 200 g sucre");
    }

    /** "These frames have no legible text" is an answer, not an error. */
    @Test
    void returnsBlankWhenVisionFindsNoText() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(visionEnvelope("   "), MediaType.APPLICATION_JSON));

        assertThat(client.transcribeFrames(UUID.randomUUID(), List.of(new byte[]{1}),
                new BudgetApproved("gemini-2.5-flash", 20))).isEmpty();
    }

    @Test
    void visionRateLimitIsRetryable() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> client.transcribeFrames(UUID.randomUUID(), List.of(new byte[]{1}),
                new BudgetApproved("gemini-2.5-flash", 20)))
                .isInstanceOf(RetryableJobException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void tooManyRequestsThrowsRetryableJobException() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.classify(UUID.randomUUID(), "text",
                new BudgetApproved("gemini-2.5-flash-lite", 500)))
                .isInstanceOf(RetryableJobException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void serverErrorThrowsRetryableJobException() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.classify(UUID.randomUUID(), "text",
                new BudgetApproved("gemini-2.5-flash-lite", 500)))
                .isInstanceOf(RetryableJobException.class);
    }

    @Test
    void badGatewayIsTreatedAsRetryableNotFatal() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"bad payload\"}"));

        // A 4xx that isn't 429 still shouldn't kill the save outright — the
        // runner's exponential backoff owns that decision, not this client.
        assertThatThrownBy(() -> client.classify(UUID.randomUUID(), "text",
                new BudgetApproved("gemini-2.5-flash-lite", 500)))
                .isInstanceOf(RetryableJobException.class);
    }

    @Test
    void digestRequestIsPlainTextWithNoSchemaAndNoImages() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.contents[0].parts[0].text").exists())
                .andExpect(jsonPath("$.contents[0].parts[1]").doesNotExist())
                // Prose, not classification: no responseSchema, same reasoning
                // as the vision-escalation request.
                .andExpect(jsonPath("$.generationConfig.responseSchema").doesNotExist())
                .andRespond(withSuccess(
                        visionEnvelope("You saved 5 items this week, mostly recipes."),
                        MediaType.APPLICATION_JSON));

        String summary = client.summarizeDigest(UUID.randomUUID(),
                List.of("recipe: Tiramisu", "movie: Interstellar"),
                new BudgetApproved("gemini-2.5-flash-lite", 500));

        assertThat(summary).isEqualTo("You saved 5 items this week, mostly recipes.");
    }

    /** Same missing-charset shape as classify and vision — a recap is free text too. */
    @Test
    void decodesDigestTextAsUtf8RegardlessOfMissingCharsetHeader() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .body(visionEnvelope("Mostly café reviews and crème brûlée recipes this week.")));

        String summary = client.summarizeDigest(UUID.randomUUID(), List.of("place: Café Nakamura"),
                new BudgetApproved("gemini-2.5-flash-lite", 500));

        assertThat(summary).isEqualTo("Mostly café reviews and crème brûlée recipes this week.");
    }

    @Test
    void digestRateLimitIsRetryable() {
        server.expect(requestTo(startsWith("https://generativelanguage.googleapis.com/v1beta/models/")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> client.summarizeDigest(UUID.randomUUID(), List.of("recipe: Tiramisu"),
                new BudgetApproved("gemini-2.5-flash-lite", 500)))
                .isInstanceOf(RetryableJobException.class)
                .hasMessageContaining("rate limit");
    }
}
