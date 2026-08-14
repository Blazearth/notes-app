package com.weavr.extraction.api;

import java.util.Optional;

import com.weavr.extraction.api.dto.ExtractRequest;
import com.weavr.extraction.api.dto.SuccessResponse;
import com.weavr.extraction.cascade.ExtractionOptions;
import com.weavr.extraction.cascade.ExtractionOrchestrator;
import com.weavr.extraction.cascade.ExtractionResult;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /extract} — the one entry point this service exposes to the
 * backend (docs/extraction-architecture.md Part D). {@code quality} in the
 * request is accepted but currently has no effect: the visual tier always
 * downloads the worst stream that still has legible text
 * ({@code weavr.ytdlp.video-format}), which is the only quality this service
 * has ever needed — carried in the contract for forward compatibility, not
 * wired to anything yet.
 */
@RestController
class ExtractController {

    private final ExtractionOrchestrator orchestrator;
    private final IdempotencyCache idempotency;

    ExtractController(ExtractionOrchestrator orchestrator, IdempotencyCache idempotency) {
        this.orchestrator = orchestrator;
        this.idempotency = idempotency;
    }

    @PostMapping(path = "/extract", produces = MediaType.APPLICATION_JSON_VALUE)
    SuccessResponse extract(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @RequestBody(required = false) ExtractRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "A url is required.");
        }

        Optional<SuccessResponse> cached = idempotency.get(idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        ExtractionOptions options = toOptions(request.options());
        ExtractionResult result = orchestrator.extract(request.url(), options);
        SuccessResponse response = SuccessResponse.of(result);
        idempotency.put(idempotencyKey, response);
        return response;
    }

    private static ExtractionOptions toOptions(ExtractRequest.Options options) {
        if (options == null) {
            return ExtractionOptions.defaults();
        }
        return new ExtractionOptions(options.audio(), options.frames(), options.maxDurationSeconds());
    }
}
