package com.weavr.api.gemini;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema pre-flight this repo's process requires before a registry
 * change ships: serialize the real {@link KnowledgeTypeRegistry#buildResponseSchema()}
 * and {@link KnowledgeTypeRegistry#buildSystemPrompt()} into one real
 * {@code generateContent} call (docs/next-phases.md, ground rule 4).
 *
 * <p><b>Opt-in — costs 1 of the 500 daily requests.</b> Set
 * {@code WEAVR_LIVE_GEMINI=1} to run it:
 *
 * <pre>
 *   WEAVR_LIVE_GEMINI=1 ./mvnw test -Dtest=KnowledgeTypeRegistrySchemaLiveTest
 * </pre>
 *
 * <p>This does not prove routing — a model that returns the wrong type for
 * ambiguous content is a prompt-tuning problem, not a schema problem. It
 * proves the {@code anyOf} the registry now builds (14 branches as of the
 * Phase 3 batch) is still inside whatever nesting/size limit the real API
 * enforces, which the 2026-08-07 pre-flight for the objectArray change
 * could only confirm by asking the API directly — {@code ListModels}
 * says nothing about request-shape limits.
 */
@EnabledIfEnvironmentVariable(named = "WEAVR_LIVE_GEMINI", matches = "1")
class KnowledgeTypeRegistrySchemaLiveTest {

    private static final String MODEL = "gemini-3.1-flash-lite";

    /** Content that should route to the newest, most structurally complex Phase 3 type. */
    private static final String RECOMMENDATION_LIST_INPUT =
            "5 underrated strategy games you need to play: 5. Into the Breach — tiny, "
            + "perfect puzzle-tactics, every loss teaches you something, on Steam and Switch. "
            + "4. Mindustry — free, open source, a factory game with tower defense bolted on, "
            + "surprisingly deep. 3. Frost Punk — city builder but every choice is a moral "
            + "trade-off, the ending will stay with you. 2. Northgard — Viking-themed 4X, "
            + "much faster matches than Civilization, great with friends. 1. Slay the Spire — "
            + "the game that defined the deckbuilder roguelike genre, still the best one. "
            + "Play them in whatever order, none of these depend on the others.";

    @Test
    void realSchemaIsAcceptedByTheRealApi() throws Exception {
        String apiKey = System.getenv("WEAVR_GEMINI_API_KEY");
        assertThat(apiKey).as("WEAVR_GEMINI_API_KEY must be set to run the live pre-flight").isNotBlank();

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", KnowledgeTypeRegistry.buildSystemPrompt()))),
                "contents", List.of(Map.of("parts", List.of(Map.of("text", RECOMMENDATION_LIST_INPUT)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", KnowledgeTypeRegistry.buildResponseSchema(),
                        "thinkingConfig", Map.of("thinkingBudget", 1024)));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + MODEL + ":generateContent?key=" + apiKey;

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode())
                .as("Gemini response body: %s", new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(200);

        JsonNode root = mapper.readTree(response.body());
        String generatedJson = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        JsonNode parsed = mapper.readTree(generatedJson);

        String knowledgeType = parsed.path("knowledgeType").asText();
        System.out.println("Schema pre-flight: knowledgeType=" + knowledgeType
                + " confidence=" + parsed.path("confidence").asDouble());
        System.out.println(parsed.toPrettyString());

        assertThat(KnowledgeTypeRegistry.isKnown(knowledgeType)).as("model returned an unregistered type").isTrue();
        assertThat(parsed.path("confidence").asDouble()).isGreaterThan(0.0);
    }
}
