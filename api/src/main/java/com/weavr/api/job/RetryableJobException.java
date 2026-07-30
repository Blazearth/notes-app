package com.weavr.api.job;

/**
 * The attempt failed but might succeed later: a network blip, a 5xx from an
 * enrichment API, a transient yt-dlp extractor error.
 *
 * <p>Increments {@code attempts} and reschedules with exponential backoff. Once
 * {@code attempts} reaches {@code max_attempts} the job is failed for good.
 */
public class RetryableJobException extends RuntimeException {

    public RetryableJobException(String message) {
        super(message);
    }

    public RetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
