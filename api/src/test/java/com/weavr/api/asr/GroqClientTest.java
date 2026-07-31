package com.weavr.api.asr;

import java.time.Duration;
import java.util.Map;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Binds {@link MockRestServiceServer} to the injected {@code RestClient.Builder}
 * so this exercises the real byte-level response handling — the same pattern
 * {@code GeminiClientTest} uses after the encoding bug, applied here from the
 * start rather than after a second incident.
 */
class GroqClientTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    private MockRestServiceServer server;
    private GroqClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GroqProperties props = new GroqProperties("test-key", "whisper-large-v3-turbo",
                Duration.ofSeconds(30), 90);
        client = new GroqClient(props, MAPPER, builder);
    }

    @Test
    void parsesTheTranscriptFromTheResponse() {
        byte[] body = MAPPER.writeValueAsBytes(Map.of("text", "first brown the onions then add the stock"));
        server.expect(requestTo(GROQ_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        String text = client.transcribe(new byte[]{1, 2, 3}, "audio.wav");

        assertThat(text).isEqualTo("first brown the onions then add the stock");
    }

    @Test
    void decodesNonAsciiTextAsUtf8RegardlessOfMissingCharsetHeader() {
        byte[] body = MAPPER.writeValueAsBytes(Map.of("text", "café société déjà vu"));
        // No Content-Type header at all — the shape that actually defeats
        // Spring's StringHttpMessageConverter charset guess (see GeminiClientTest).
        server.expect(requestTo(GROQ_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).body(body));

        String text = client.transcribe(new byte[]{1, 2, 3}, "audio.wav");

        assertThat(text).isEqualTo("café société déjà vu");
    }

    @Test
    void tooManyRequestsThrowsRetryableJobException() {
        server.expect(requestTo(GROQ_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        assertThatThrownBy(() -> client.transcribe(new byte[]{1, 2, 3}, "audio.wav"))
                .isInstanceOf(RetryableJobException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void serverErrorThrowsRetryableJobException() {
        server.expect(requestTo(GROQ_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.transcribe(new byte[]{1, 2, 3}, "audio.wav"))
                .isInstanceOf(RetryableJobException.class);
    }
}
