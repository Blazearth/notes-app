package com.weavr.api.pipeline.health;

/**
 * Why one provider attempt did (or didn't) produce source data — the
 * operator's view, not the user's. {@code YtDlpErrors} already owns what the
 * user is told and whether the job retries; this answers a different
 * question: <em>which way is extraction breaking, on which platform?</em>
 *
 * <p>Kept deliberately coarse. A category earns its place by pointing at a
 * different response ({@code BOT_CHECK} → an IP problem no flag fixes;
 * {@code TIMEOUT} → possibly a timeout/flag problem; {@code EXTRACTOR_ERROR}
 * → yt-dlp needs a version bump), not by describing a message more precisely.
 */
public enum ExtractionFailureCategory {
    SUCCESS,
    /** The process or socket ran out of time. */
    TIMEOUT,
    HTTP_403,
    /** Rate-limited or quota-exhausted, whatever status code carried it. */
    HTTP_429,
    /** YouTube's "Sign in to confirm you're not a bot" — the datacenter-IP block. */
    BOT_CHECK,
    /** A login, age or membership wall in front of the content. */
    AUTH_REQUIRED,
    /** Deleted, private or never existed. Not a breakage — nothing to fix. */
    CONTENT_UNAVAILABLE,
    /** No extractor for this URL, or an ID that can't be a real one. */
    UNSUPPORTED,
    /** yt-dlp reached the site but couldn't make sense of it — usually a stale extractor. */
    EXTRACTOR_ERROR,
    /** An upstream API/server answered with an error or a response we couldn't parse. */
    PROVIDER_ERROR,
    NETWORK_ERROR,
    UNKNOWN
}
