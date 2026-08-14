package com.weavr.extraction.api.dto;

import com.weavr.extraction.error.ErrorCode;

/** The {@code {"success": false, "error": {...}}} envelope (docs/extraction-architecture.md Part D). */
public record ErrorResponse(boolean success, Body error) {

    public record Body(String code, String message, boolean retryable) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(false, new Body(code.name(), message, code.retryable()));
    }
}
