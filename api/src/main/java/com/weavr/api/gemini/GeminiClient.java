package com.weavr.api.gemini;

import java.time.Instant;
import java.util.Base64;
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

    // RestClient.Builder is injected (Spring Boot auto-configures a prototype
    // bean) rather than built ad hoc, so tests can bind MockRestServiceServer
    // to it instead of hitting the real Gemini API.
    GeminiClient(GeminiProperties props, ObjectMapper objectMapper, JdbcClient jdbc,
                RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.http = restClientBuilder
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

    /**
     * A general structured-output call, for features that need JSON back but
     * are not classification.
     *
     * <p>Exists so an Act owns its own prompt and response schema rather than
     * this class growing a method per feature — but still routes through the
     * one place that holds the {@link BudgetApproved} requirement and the
     * {@code gemini_calls} logging, which is the property worth protecting.
     *
     * @param purpose recorded in {@code gemini_calls}, so each feature's share
     *                of the daily budget is measurable separately
     * @return the parsed JSON the model produced
     */
    public JsonNode generateJson(UUID saveId, String systemPrompt, String userText,
                                 Map<String, Object> responseSchema, String purpose,
                                 BudgetApproved budget) {
        String model = budget.model();
        Instant callStart = Instant.now();
        String outcome = "success";
        int inputTokens = 0;
        int outputTokens = 0;

        try {
            Map<String, Object> body = Map.of(
                    "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                    "contents", List.of(Map.of("parts", List.of(
                            Map.of("text", userText.replaceAll("\\s+", " ").strip())))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "responseSchema", responseSchema,
                            "thinkingConfig", Map.of("thinkingBudget", 1024)));

            byte[] raw = http.post()
                    .uri(GEMINI_BASE + model + ":generateContent?key=" + props.apiKey())
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    // Raw bytes, not String — see classify() for why.
                    .body(byte[].class);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode usage = root.path("usageMetadata");
            inputTokens = usage.path("promptTokenCount").asInt(0);
            outputTokens = usage.path("candidatesTokenCount").asInt(0);

            String generated = root
                    .path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            return objectMapper.readTree(generated);

        } catch (HttpClientErrorException e) {
            outcome = "client_error_" + e.getStatusCode().value();
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RetryableJobException("Gemini rate limit hit (429), will retry.", e);
            }
            throw new RetryableJobException("Gemini returned " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            outcome = "server_error_" + e.getStatusCode().value();
            throw new RetryableJobException(
                    "Gemini is temporarily unavailable (" + e.getStatusCode() + ").", e);
        } catch (Exception e) {
            outcome = "error";
            throw new RetryableJobException("An error occurred calling Gemini: " + e.getMessage(), e);
        } finally {
            logCall(saveId, model, inputTokens, outputTokens, 0.0, outcome, callStart, purpose);
        }
    }

    /**
     * Tier 2 of the visual cascade: read the text off these frames when local
     * OCR came back below the repair floor.
     *
     * <p><b>This returns text, not a classification, and that is deliberate.</b>
     * The frames go in, a verbatim transcription comes out, and it then flows
     * through the ordinary single classify call like any other save — so the
     * knowledge-type schema, the few-shot examples and the confidence routing
     * all keep working unchanged, and the vision model is asked to do the one
     * thing it is here for.
     *
     * <p>It does mean an escalating save costs two requests rather than one.
     * That is the accepted price of the escalation path existing at all: the
     * alternative — making the classify call itself multimodal — needs the
     * frames to survive from {@code process_save} into {@code classify_save},
     * which means either persisting base64 images into JSONB or downloading the
     * video a second time. Both are worse than one extra Flash request on the
     * minority of saves that get here, and the fallback pool's small RPD caps
     * how many that can ever be.
     *
     * @param frames JPEG bytes, in reading order, already ranked and trimmed by
     *               the caller — this method does not decide how many to send
     * @return the transcribed text, possibly blank if the frames carry none
     */
    public String transcribeFrames(UUID saveId, List<byte[]> frames, BudgetApproved budget) {
        String model = budget.model();
        Instant callStart = Instant.now();
        String outcome = "success";
        int inputTokens = 0;
        int outputTokens = 0;

        try {
            String url = GEMINI_BASE + model + ":generateContent?key=" + props.apiKey();

            log.debug("Gemini vision OCR: save={} model={} frames={}", saveId, model, frames.size());

            byte[] rawResponse = http.post()
                    .uri(url)
                    .body(objectMapper.writeValueAsString(buildVisionRequest(frames)))
                    .retrieve()
                    // Raw bytes, not String — same reason as classify() above.
                    .body(byte[].class);

            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode usage = root.path("usageMetadata");
            inputTokens = usage.path("promptTokenCount").asInt(0);
            outputTokens = usage.path("candidatesTokenCount").asInt(0);

            String text = root
                    .path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            log.info("Gemini vision OCR save={} read {} chars from {} frames in={}tok out={}tok",
                    saveId, text.length(), frames.size(), inputTokens, outputTokens);

            return text == null ? "" : text.strip();

        } catch (HttpClientErrorException e) {
            outcome = "client_error_" + e.getStatusCode().value();
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RetryableJobException("Gemini rate limit hit (429) on vision OCR.", e);
            }
            throw new RetryableJobException(
                    "Gemini vision OCR returned " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            outcome = "server_error_" + e.getStatusCode().value();
            throw new RetryableJobException(
                    "Gemini is temporarily unavailable (" + e.getStatusCode() + "), will retry.", e);
        } catch (Exception e) {
            outcome = "error";
            throw new RetryableJobException(
                    "An error occurred calling Gemini vision OCR: " + e.getMessage(), e);
        } finally {
            logCall(saveId, model, inputTokens, outputTokens, 0.0, outcome, callStart, "ocr_vision");
        }
    }

    /**
     * Frames plus a transcription instruction. No {@code responseSchema} here —
     * the output is prose, and forcing JSON would only add an escaping layer
     * around a string.
     */
    private Map<String, Object> buildVisionRequest(List<byte[]> frames) {
        List<Map<String, Object>> parts = new java.util.ArrayList<>();
        parts.add(Map.of("text", """
                These are frames from one short video, in order. Transcribe every \
                piece of text visible in them — overlay captions, ingredient lists, \
                on-screen instructions, labels, prices.

                Rules:
                - Transcribe verbatim. Do not summarise, translate or reword.
                - The same text often persists across several frames. Write it once.
                - Preserve the reading order and line breaks of the original layout.
                - Ignore platform chrome: usernames, follower counts, like and share \
                  buttons, watermarks, progress bars.
                - If the frames contain no legible text at all, reply with nothing.
                """));
        for (byte[] frame : frames) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", MediaType.IMAGE_JPEG_VALUE,
                    "data", Base64.getEncoder().encodeToString(frame))));
        }

        return Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        // Transcription, not reasoning — thinking budget buys
                        // nothing here and the frames are already the expensive part.
                        "temperature", 0.0
                )
        );
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

    private void logCall(UUID saveId, String model, int inputTokens, int outputTokens,
                         double confidence, String outcome, Instant callStart) {
        logCall(saveId, model, inputTokens, outputTokens, confidence, outcome, callStart,
                "classify_save");
    }

    /**
     * Logs the call to {@code gemini_calls} for observability.
     *
     * <p>{@code purpose} separates the classify call from a vision escalation,
     * which is what makes the escalation rate measurable rather than guessed —
     * the one number Phase 4 has to watch, since both failure directions
     * (burning the Flash pool, shipping silent hallucinations) are otherwise
     * invisible.
     */
    private void logCall(UUID saveId, String model, int inputTokens, int outputTokens,
                         double confidence, String outcome, Instant callStart, String purpose) {
        try {
            long ms = java.time.Duration.between(callStart, Instant.now()).toMillis();
            log.debug("Gemini call: save={} model={} outcome={} latency={}ms", saveId, model, outcome, ms);

            jdbc.sql("""
                            insert into gemini_calls
                                (save_id, model, purpose, input_tokens, output_tokens, confidence, outcome)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .param(saveId)
                    .param(model)
                    .param(purpose)
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
