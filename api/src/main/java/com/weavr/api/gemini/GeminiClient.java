package com.weavr.api.gemini;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.RetryableJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes a single classify-and-extract call to the Gemini REST API and logs
 * every invocation to {@code gemini_calls}.
 *
 * <p>Callers MUST hold a {@link BudgetApproved} token — the compiler enforces
 * this, so the budget guard cannot be accidentally bypassed.
 *
 * <p>Structured output: the request sends {@code responseMimeType =
 * "application/json"} and a {@code responseSchema} built from
 * {@link KnowledgeTypeRegistry}. Gemini returns valid JSON directly;
 * no free-text parsing is needed.
 *
 * <p>Thinking: {@code thinkingBudget = 1024} is set explicitly — Flash Lite
 * ships with thinking effectively disabled by default (CLAUDE.md).
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final String GEMINI_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestClient http;
    private final GeminiProperties props;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    GeminiClient(GeminiProperties props, ObjectMapper objectMapper, JdbcClient jdbc) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.http = RestClient.builder()
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Classifies and extracts structured data from {@code text} using the
     * model specified in {@code budget}.
     *
     * @param saveId UUID of the save — used for observability logging only
     * @param text   the assembled text blob (captions + metadata + OCR)
     * @param budget proof that the daily budget has been checked
     * @return the parsed Gemini response
     * @throws RetryableJobException on transient API errors (5xx, 429)
     */
    public GeminiResponse classify(UUID saveId, String text, BudgetApproved budget) {
        String model = budget.model();
        Instant callStart = Instant.now();
        String outcome = "success";
        int inputTokens = 0;
        int outputTokens = 0;
        double confidence = 0.0;

        try {
            Map<String, Object> requestBody = buildRequest(text, model);
            String url = GEMINI_BASE + model + ":generateContent?key=" + props.apiKey();

            log.debug("Gemini classify: save={} model={}", saveId, model);

            // Read as raw bytes, not String: JSON is UTF-8 by spec (RFC 8259), but
            // Gemini's response has no charset param on its Content-Type header, so
            // Spring's StringHttpMessageConverter falls back to a platform-dependent
            // default rather than UTF-8. That mis-decoded multi-byte characters
            // (accented names, non-English text) into mojibake before Jackson ever
            // saw them — e.g. "café" arrived as "cafÃ©". Jackson's byte-based
            // readTree follows the JSON spec directly, so this sidesteps the
            // charset guess entirely instead of pinning a specific one.
            byte[] rawResponse = http.post()
                    .uri(url)
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(byte[].class);

            JsonNode root = objectMapper.readTree(rawResponse);

            // Token usage
            JsonNode usage = root.path("usageMetadata");
            inputTokens = usage.path("promptTokenCount").asInt(0);
            outputTokens = usage.path("candidatesTokenCount").asInt(0);

            // Extract the generated JSON from the first candidate's text.
            String generatedJson = root
                    .path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            JsonNode parsed = objectMapper.readTree(generatedJson);
            String knowledgeType = parsed.path("knowledgeType").asText("other");
            confidence = parsed.path("confidence").asDouble(0.5);

            // Collect the type-specific fields (everything except the common ones).
            Map<String, Object> structuredData = objectMapper.convertValue(parsed, Map.class);
            structuredData.remove("knowledgeType");
            structuredData.remove("confidence");

            log.info("Gemini classified save={} type={} confidence={} model={} in={}tok out={}tok",
                    saveId, knowledgeType, confidence, model, inputTokens, outputTokens);

            return new GeminiResponse(knowledgeType, confidence, structuredData,
                    inputTokens, outputTokens, model);

        } catch (HttpClientErrorException e) {
            outcome = "client_error_" + e.getStatusCode().value();
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                // 429 from Gemini — transient, the runner will back off.
                throw new RetryableJobException(
                        "Gemini rate limit hit (429), will retry.", e);
            }
            // 4xx other than 429 are usually bad payloads — retryable with backoff.
            throw new RetryableJobException(
                    "Gemini returned an unexpected client error: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            outcome = "server_error_" + e.getStatusCode().value();
            throw new RetryableJobException(
                    "Gemini is temporarily unavailable (" + e.getStatusCode() + "), will retry.", e);
        } catch (Exception e) {
            outcome = "error";
            throw new RetryableJobException(
                    "An error occurred calling Gemini: " + e.getMessage(), e);
        } finally {
            logCall(saveId, model, inputTokens, outputTokens, confidence, outcome, callStart);
        }
    }

    /** Builds the full Gemini request body. */
    private Map<String, Object> buildRequest(String text, String model) {
        String systemPrompt = KnowledgeTypeRegistry.buildSystemPrompt();
        Map<String, Object> responseSchema = KnowledgeTypeRegistry.buildResponseSchema();

        // Whitespace-collapse the text to remove VTT/OCR artifacts.
        String cleanText = text.replaceAll("\\s+", " ").strip();

        return Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", cleanText)))
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", responseSchema,
                        "thinkingConfig", Map.of(
                                // Tokens are cheap, requests are scarce (CLAUDE.md).
                                // Enable thinking explicitly — Flash Lite has it off by default.
                                "thinkingBudget", 1024
                        )
                )
        );
    }

    /** Logs the call to {@code gemini_calls} for observability. */
    private void logCall(UUID saveId, String model, int inputTokens, int outputTokens,
                         double confidence, String outcome, Instant callStart) {
        try {
            long ms = java.time.Duration.between(callStart, Instant.now()).toMillis();
            log.debug("Gemini call: save={} model={} outcome={} latency={}ms", saveId, model, outcome, ms);

            jdbc.sql("""
                            insert into gemini_calls
                                (save_id, model, purpose, input_tokens, output_tokens, confidence, outcome)
                            values (?, ?, 'classify_save', ?, ?, ?, ?)
                            """)
                    .param(saveId)
                    .param(model)
                    .param(inputTokens)
                    .param(outputTokens)
                    .param(confidence > 0 ? confidence : null)
                    .param(outcome)
                    .update();
        } catch (Exception e) {
            // Logging failure must never crash the pipeline.
            log.warn("Failed to log Gemini call for save {}: {}", saveId, e.getMessage());
        }
    }
}
