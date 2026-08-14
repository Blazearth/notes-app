package com.weavr.extraction.api;

import com.weavr.extraction.api.dto.ErrorResponse;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place that turns any failure into the wire error body
 * (docs/extraction-architecture.md Part D) — no handler anywhere else in this
 * service branches on an error string. {@code message} is always
 * {@link ExtractionException#userMessage()}: user-safe prose with no stack
 * traces, no paths, no upstream URLs. Everything else is logged here, with
 * the cause, and never returned to the caller.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ExtractionException.class)
    ResponseEntity<ErrorResponse> handleExtraction(ExtractionException e) {
        if (e.code() == ErrorCode.INTERNAL_ERROR || e.code().retryable()) {
            log.warn("[{}] {}", e.code(), e.getMessage(), e.getCause());
        } else {
            log.info("[{}] {}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.code().httpStatus()).body(ErrorResponse.of(e.code(), e.userMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ErrorCode.INVALID_URL, "That request body isn't valid JSON."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected failure", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Something went wrong on Weavr's end. We'll try again."));
    }
}
