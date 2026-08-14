package com.weavr.api.pipeline;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls the dedicated extraction service ({@code POST /extract}) and maps its
 * response to an {@link ExtractionCascade.Extraction} so
 * {@link com.weavr.api.save.ProcessSaveHandler} has the same shape as before.
 *
 * <p>Gated by {@code weavr.extraction.url} — when blank the bean still
 * constructs but {@link #enabled()} returns false, and the caller falls back to
 * the in-process {@link ExtractionCascade}. No feature flag needed: blank URL
 * means "not deployed yet."
 */
@Component
public class ExtractionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ExtractionServiceClient.class);

    @ConfigurationProperties(prefix = "weavr.extraction-service")
    public record Props(String url, String sharedSecret) {
        public boolean configured() {
            return url != null && !url.isBlank()
                    && sharedSecret != null && !sharedSecret.isBlank();
        }
    }

    private final Props props;
    private final RestClient http;
    private final ObjectMapper objectMapper;

    ExtractionServiceClient(Props props,
                            RestClient.Builder restClientBuilder,
                            ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + (props.sharedSecret() != null ? props.sharedSecret() : ""))
                .build();
    }

    public boolean enabled() {
        return props.configured();
    }

    /**
     * Calls {@code POST /extract} and returns the assembled text + thumbnail.
     * Throws permanent or retryable exceptions matching the existing cascade's
     * contract so the job runner handles them identically.
     */
    public ExtractionCascade.Extraction extract(String url, UUID saveId) {
        String body = objectMapper.createObjectNode()
                .put("url", url)
                .set("options", objectMapper.createObjectNode()
                        .put("audio", false)
                        .put("frames", false)
                        .put("maxDurationSeconds", 90))
                .toString();

        log.debug("extraction-service: POST /extract url={} saveId={}", url, saveId);

        String responseBody;
        try {
            responseBody = http.post()
                    .uri(props.url() + "/extract")
                    .header("Idempotency-Key", saveId.toString())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            // 4xx from extraction service
            return mapErrorResponse(e.getResponseBodyAsString(), e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            // 5xx from extraction service
            return mapErrorResponse(e.getResponseBodyAsString(), e.getStatusCode().value());
        } catch (Exception e) {
            throw new RetryableJobException("extraction-service unreachable: " + e.getMessage(), e);
        }

        JsonNode root = objectMapper.readTree(responseBody);
        if (!root.path("success").asBoolean(false)) {
            return mapErrorResponse(responseBody, 500);
        }

        JsonNode result = root.path("result");
        String text = result.path("text").asText("");
        String source = result.path("source").asText("extraction-service");
        boolean needsTranscription = result.path("needsTranscription").asBoolean(false);

        if (text.isBlank() && !needsTranscription) {
            throw new PermanentJobException("no_text_extracted",
                    "Weavr couldn't find any text in that post to work with.");
        }

        // Build a SourceMetadata-compatible object from the wire response
        JsonNode meta = result.path("metadata");
        String title = meta.path("title").asText(null);
        String description = meta.path("description").asText(null);
        String uploader = meta.path("uploader").asText(null);
        Double duration = meta.path("durationSeconds").isNumber()
                ? meta.path("durationSeconds").asDouble() : null;
        String thumbnailUrl = extractThumbnailFromArtifacts(result.path("artifacts"));

        // Build captionLanguages list
        List<String> captionLanguages = new java.util.ArrayList<>();
        meta.path("captionLanguages").forEach(n -> captionLanguages.add(n.asText()));

        com.weavr.api.pipeline.ytdlp.SourceMetadata sourceMetadata =
                new com.weavr.api.pipeline.ytdlp.SourceMetadata(
                        meta.path("sourceId").asText(null),
                        title, description, uploader,
                        duration, thumbnailUrl,
                        captionLanguages, List.of(),
                        meta.path("pinnedComment").asText(null));

        log.info("extraction-service: source={} textLen={} saveId={}", source, text.length(), saveId);
        return new ExtractionCascade.Extraction(text, source, sourceMetadata);
    }

    private String extractThumbnailFromArtifacts(JsonNode artifacts) {
        if (!artifacts.isArray()) return null;
        for (JsonNode artifact : artifacts) {
            if ("thumbnail".equals(artifact.path("kind").asText())) {
                // The ref is an opaque HMAC ref — we only use the url field if present
                String url = artifact.path("url").asText(null);
                if (url != null && !url.isBlank()) return url;
            }
        }
        return null;
    }

    private ExtractionCascade.Extraction mapErrorResponse(String body, int status) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            String code = error.path("code").asText("INTERNAL_ERROR");
            String message = error.path("message").asText("Extraction failed.");
            boolean retryable = error.path("retryable").asBoolean(true);

            // Map error codes to our existing permanent/retryable exceptions
            if (!retryable) {
                String errorCode = switch (code) {
                    case "UNSUPPORTED_URL" -> "unsupported_source";
                    case "CONTENT_UNAVAILABLE" -> "content_unavailable";
                    case "AUTH_REQUIRED" -> "auth_required";
                    case "INVALID_URL" -> "invalid_url";
                    default -> "extract_failed";
                };
                throw new PermanentJobException(errorCode, message);
            } else {
                throw new RetryableJobException("extraction-service error [" + code + "]: " + message);
            }
        } catch (PermanentJobException | RetryableJobException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableJobException("extraction-service returned HTTP " + status);
        }
    }
}
