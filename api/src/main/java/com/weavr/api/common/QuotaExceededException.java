package com.weavr.api.common;

/**
 * A free-tier cap the caller has hit and can escape by subscribing.
 *
 * <p>Rendered as <strong>402 Payment Required</strong>, not 403: the difference
 * matters to the client, which shows a paywall for one and an error for the
 * other. {@link #quota()} names which cap ({@code saves}, {@code acts}) so the
 * copy can be specific about what ran out.
 */
public class QuotaExceededException extends RuntimeException {

    private final String quota;
    private final int limit;
    private final int used;

    public QuotaExceededException(String quota, int limit, int used, String message) {
        super(message);
        this.quota = quota;
        this.limit = limit;
        this.used = used;
    }

    public String quota() {
        return quota;
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return used;
    }
}
