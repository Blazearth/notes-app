package com.weavr.extraction.error;

/**
 * The ten wire error codes, in one place — docs/extraction-architecture.md
 * Part D, "The error model". {@code retryable} is decided here, by the
 * service, and obeyed by the backend rather than each side guessing; this is
 * the same classification {@code YtDlpErrors} already performs in the
 * monolith, promoted to the wire so it survives the process boundary.
 */
public enum ErrorCode {

    /** Unparseable, or scheme is not http/https — or the target itself is unfetchable (SSRF-rejected, unresolvable). */
    INVALID_URL(400, false),
    /** No extractor, and not a readable page either. */
    UNSUPPORTED_URL(422, false),
    /** Deleted, private, age-restricted, paywalled, or nothing usable was found there. */
    CONTENT_UNAVAILABLE(404, false),
    /** The source demands a login this service does not and will not hold. */
    AUTH_REQUIRED(403, false),
    /** Unknown extractor breakage, assumed transient. */
    EXTRACTION_FAILED(502, true),
    /** Provider 429/5xx, bot check, rate limit. */
    TEMPORARY_PROVIDER_ERROR(503, true),
    /** Connection reset, DNS, unreachable. */
    NETWORK_ERROR(502, true),
    /** Per-request or whole-extraction deadline exceeded. */
    TIMEOUT(504, true),
    /** Artifact write to the store failed. */
    STORAGE_ERROR(502, true),
    /** Anything unclassified — retryable by default, as the monolith's own catch-all already is. */
    INTERNAL_ERROR(500, true);

    private final int httpStatus;
    private final boolean retryable;

    ErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
