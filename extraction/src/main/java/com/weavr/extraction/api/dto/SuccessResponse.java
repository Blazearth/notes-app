package com.weavr.extraction.api.dto;

import com.weavr.extraction.cascade.ExtractionResult;

/**
 * The {@code {"success": true, "result": {...}}} envelope. Wraps
 * {@link ExtractionResult} directly rather than a parallel DTO — its field
 * names already are the wire contract (docs/extraction-architecture.md Part D).
 */
public record SuccessResponse(boolean success, ExtractionResult result) {

    public static SuccessResponse of(ExtractionResult result) {
        return new SuccessResponse(true, result);
    }
}
