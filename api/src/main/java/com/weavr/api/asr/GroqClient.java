package com.weavr.api.asr;

import com.weavr.api.job.RetryableJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A single transcription call to Groq's Whisper endpoint — ASR, the last
 * resort of the extraction cascade, reached only when captions and metadata
 * both come up empty.
 *
 * <p>A different provider from Gemini deliberately: its own key, its own
 * rate-limit pool, so a transcription never spends any of the scarce Gemini
 * daily budget (CLAUDE.md § the request budget).
 */
@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    private final RestClient http;
    private final GroqProperties props;
    private final ObjectMapper objectMapper;

    // RestClient.Builder is injected, not built ad hoc, so tests can bind
    // MockRestServiceServer to it instead of hitting the real Groq API — the
    // same fix applied to GeminiClient after the encoding bug.
    GroqClient(GroqProperties props, ObjectMapper objectMapper,
              @Qualifier("groq") RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder.build();
    }

    /**
     * @param wavBytes 16 kHz mono WAV, already downmixed by {@link
     *                 com.weavr.api.pipeline.audio.FfmpegClient}
     * @return the transcript, or blank if Groq returned no speech
     * @throws RetryableJobException on transient API errors (5xx, 429)
     */
    public String transcribe(byte[] wavBytes, String filename) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(wavBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.part("model", props.model());
        body.part("response_format", "json");

        try {
            // Read as raw bytes, not String: the same charset trap that hit
            // GeminiClient applies to any JSON API whose Content-Type omits a
            // charset. Jackson's byte-based readTree follows RFC 8259
            // directly instead of guessing.
            byte[] rawResponse = http.post()
                    .uri(GROQ_URL)
                    .header("Authorization", "Bearer " + props.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(byte[].class);

            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("text").asText("");
            log.info("Groq transcribed {} bytes -> {} chars", wavBytes.length, text.length());
            return text;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RetryableJobException("Groq rate limit hit (429), will retry.", e);
            }
            throw new RetryableJobException(
                    "Groq returned an unexpected client error: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableJobException(
                    "Groq is temporarily unavailable (" + e.getStatusCode() + "), will retry.", e);
        } catch (Exception e) {
            throw new RetryableJobException("An error occurred calling Groq: " + e.getMessage(), e);
        }
    }
}
