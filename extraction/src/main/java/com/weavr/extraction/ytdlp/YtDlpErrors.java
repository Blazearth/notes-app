package com.weavr.extraction.ytdlp;

import java.util.List;
import java.util.Locale;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.slf4j.LoggerFactory;

/**
 * Maps yt-dlp's stderr onto something a caller can read, and decides whether
 * retrying could ever help. Classification rules ported unchanged from
 * {@code api}'s {@code YtDlpErrors}; only the exception it raises differs —
 * here it produces the wire {@link ErrorCode} directly (docs/extraction-architecture.md
 * Part D: "the classification YtDlpErrors already performs, promoted to the
 * wire so it survives the process boundary"), where the monolith's copy
 * produces a job-runner exception.
 *
 * <p>Extractors break when platforms change; that is routine, not exceptional.
 * Anything unrecognised is therefore treated as <em>retryable</em>
 * ({@link ErrorCode#EXTRACTION_FAILED}), so a transient breakage recovers on
 * its own once the container updates yt-dlp.
 */
public final class YtDlpErrors {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(YtDlpErrors.class);

    /**
     * @param code        the wire error code
     * @param internalKey stable key, safe to branch on internally (e.g. "unsupported_source")
     * @param userMessage shown to the user; no internals
     */
    public record Classification(ErrorCode code, String internalKey, String userMessage) {
    }

    private record Rule(List<String> needles, ErrorCode code, String internalKey, String userMessage) {
    }

    /** Order matters: the first match wins. */
    private static final List<Rule> PERMANENT = List.of(
            new Rule(List.of("unsupported url", "no suitable extractor"),
                    ErrorCode.UNSUPPORTED_URL, "unsupported_source",
                    "Weavr can't read links from this site yet."),
            new Rule(List.of("video unavailable", "this video has been removed", "content isn't available",
                            "video has been removed", "no longer available"),
                    ErrorCode.CONTENT_UNAVAILABLE, "content_unavailable",
                    "That post isn't available any more."),
            new Rule(List.of("private video", "this video is private", "requested content is not available"),
                    ErrorCode.CONTENT_UNAVAILABLE, "content_private",
                    "That post is private, so Weavr can't open it."),
            new Rule(List.of("sign in to confirm your age", "age-restricted", "age restricted"),
                    ErrorCode.CONTENT_UNAVAILABLE, "age_restricted",
                    "That post is age-restricted and can't be saved."),
            new Rule(List.of("members-only", "join this channel", "requires payment", "paid content"),
                    ErrorCode.CONTENT_UNAVAILABLE, "content_paywalled",
                    "That post is behind a paywall."),
            new Rule(List.of("is not a valid url", "unable to parse"),
                    ErrorCode.INVALID_URL, "bad_url",
                    "That doesn't look like a link Weavr can open."));

    /**
     * Retryable, but worth naming so the log says what happened. Login walls sit
     * here deliberately: Instagram increasingly requires authentication, and
     * using a personal account's cookies risks a ban — so unauthenticated
     * failure is an acceptable outcome that may simply succeed later from a
     * different IP or provider.
     */
    private static final List<Rule> RETRYABLE = List.of(
            new Rule(List.of("sign in to confirm you're not a bot", "login required", "requires login",
                            "rate-limit", "rate limit", "http error 429", "too many requests"),
                    ErrorCode.TEMPORARY_PROVIDER_ERROR, "source_blocked",
                    "That site is blocking Weavr right now. We'll try again."),
            new Rule(List.of("http error 5", "temporary failure", "connection reset", "timed out",
                            "network is unreachable", "unable to download"),
                    ErrorCode.NETWORK_ERROR, "source_unreachable",
                    "We couldn't reach that link. We'll try again."));

    private YtDlpErrors() {
    }

    public static Classification classify(String stderr) {
        String haystack = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);

        for (Rule rule : PERMANENT) {
            if (matches(haystack, rule)) {
                return new Classification(rule.code(), rule.internalKey(), rule.userMessage());
            }
        }
        for (Rule rule : RETRYABLE) {
            if (matches(haystack, rule)) {
                return new Classification(rule.code(), rule.internalKey(), rule.userMessage());
            }
        }

        // Unknown: assume the extractor broke and will be fixed, rather than
        // permanently failing an extraction we might well handle tomorrow.
        return new Classification(ErrorCode.EXTRACTION_FAILED, "extract_failed",
                "We couldn't read that link. We'll try again.");
    }

    private static boolean matches(String haystack, Rule rule) {
        return rule.needles().stream().anyMatch(haystack::contains);
    }

    /**
     * Classifies and converts in one step, so every caller — the caption
     * fetch, the metadata probe, the audio/video download — turns a failed
     * yt-dlp run into the same {@link ExtractionException} without repeating
     * the classify-then-branch dance.
     */
    public static ExtractionException toException(YtDlpFailedException e) {
        Classification classification = classify(e.stderr());
        log.warn("yt-dlp stderr [{}]: {}", classification.internalKey(), e.stderr());
        return new ExtractionException(classification.code(), classification.userMessage(), e);
    }
}
