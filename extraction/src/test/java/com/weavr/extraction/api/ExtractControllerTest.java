package com.weavr.extraction.api;

import java.util.List;

import com.weavr.extraction.auth.AuthProperties;
import com.weavr.extraction.cascade.ExtractionMetadata;
import com.weavr.extraction.cascade.ExtractionOptions;
import com.weavr.extraction.cascade.ExtractionOrchestrator;
import com.weavr.extraction.cascade.ExtractionResult;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@code POST /extract} through the real filter chain and
 * {@link ApiExceptionHandler} — Phase 3's own gate, verbatim: "the service
 * answers every error code correctly against a fixture suite, and never
 * reports partial success" (docs/extraction-architecture.md Phase 3).
 */
@WebMvcTest(controllers = ExtractController.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "weavr.extraction.auth.shared-secret=test-secret")
class ExtractControllerTest {

    private static final String AUTH_HEADER = "Bearer test-secret";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ExtractionOrchestrator orchestrator;

    @MockitoBean
    IdempotencyCache idempotency;

    @Test
    void rejectsARequestWithNoBearerToken() throws Exception {
        mockMvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/reel"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    @Test
    void successfulExtractionReturnsTheDocumentedEnvelope() throws Exception {
        when(idempotency.get(anyString())).thenReturn(java.util.Optional.empty());
        ExtractionResult result = new ExtractionResult("captions", "some text", false, false,
                new ExtractionMetadata("youtube", "abc123", "Title", "desc", "uploader", 42.0, List.of("en"), null),
                List.of());
        when(orchestrator.extract(anyString(), any(ExtractionOptions.class))).thenReturn(result);

        mockMvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", AUTH_HEADER)
                        .content("""
                                {"url":"https://www.youtube.com/watch?v=abc123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.source").value("captions"))
                .andExpect(jsonPath("$.result.text").value("some text"))
                .andExpect(jsonPath("$.result.metadata.platform").value("youtube"));
    }

    @Test
    void aMissingUrlIsRejectedBeforeReachingTheOrchestrator() throws Exception {
        mockMvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", AUTH_HEADER)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_URL"));
    }

    /**
     * Every one of the ten wire codes, driven through the real exception
     * handler rather than asserted against {@link ErrorCode} in isolation —
     * this is what actually proves the HTTP status and body a caller sees.
     */
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyErrorCodeMapsToItsDocumentedHttpStatusAndRetryableFlag(ErrorCode code) throws Exception {
        when(idempotency.get(anyString())).thenReturn(java.util.Optional.empty());
        when(orchestrator.extract(anyString(), any(ExtractionOptions.class)))
                .thenThrow(new ExtractionException(code, "a user-safe message"));

        mockMvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", AUTH_HEADER)
                        .content("""
                                {"url":"https://example.com/x"}"""))
                .andExpect(status().is(code.httpStatus()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code.name()))
                .andExpect(jsonPath("$.error.retryable").value(code.retryable()))
                .andExpect(jsonPath("$.error.message").value("a user-safe message"));
    }

    @Test
    void anUnexpectedExceptionNeverLeaksInternalsAndIsRetryable() throws Exception {
        when(idempotency.get(anyString())).thenReturn(java.util.Optional.empty());
        when(orchestrator.extract(anyString(), any(ExtractionOptions.class)))
                .thenThrow(new RuntimeException("some internal NullPointerException at line 42 of SecretClass.java"));

        mockMvc.perform(post("/extract").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", AUTH_HEADER)
                        .content("""
                                {"url":"https://example.com/x"}"""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SecretClass"))));
    }
}
