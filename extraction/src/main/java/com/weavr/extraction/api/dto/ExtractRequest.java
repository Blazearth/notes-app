package com.weavr.extraction.api.dto;

/** The {@code POST /extract} request body (docs/extraction-architecture.md Part D). */
public record ExtractRequest(String url, Options options) {

    public record Options(boolean audio, boolean frames, String quality, Integer maxDurationSeconds) {
    }
}
