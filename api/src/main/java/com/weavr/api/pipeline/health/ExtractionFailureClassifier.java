package com.weavr.api.pipeline.health;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;

import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

/**
 * Maps a failed provider call onto an {@link ExtractionFailureCategory}.
 *
 * <p>Needles are the stable phrases yt-dlp has printed for years, not
 * per-extractor wording — the aim is "which bucket", and an unrecognised
 * message landing in {@code EXTRACTOR_ERROR}/{@code UNKNOWN} is an acceptable
 * answer, not a bug to chase with another needle.
 *
 * <p>Order matters: first match wins. Bot check is tested before 429/login
 * because YouTube's bot-check text also mentions signing in; login walls are
 * tested before 429 because Instagram's block reads "rate-limit reached or
 * login required" and behaves as a login wall (it doesn't clear by waiting).
 */
public final class ExtractionFailureClassifier {

    private record Rule(ExtractionFailureCategory category, List<String> needles) {
    }

    private static final List<Rule> YT_DLP_RULES = List.of(
            new Rule(ExtractionFailureCategory.BOT_CHECK, List.of("not a bot")),
            new Rule(ExtractionFailureCategory.UNSUPPORTED, List.of("unsupported url", "no suitable extractor")),
            new Rule(ExtractionFailureCategory.AUTH_REQUIRED, List.of(
                    "login required", "requires login", "log in to", "sign in to confirm your age",
                    "age-restricted", "age restricted", "members-only", "join this channel")),
            new Rule(ExtractionFailureCategory.HTTP_429, List.of(
                    "http error 429", "too many requests", "rate-limit", "rate limit")),
            new Rule(ExtractionFailureCategory.HTTP_403, List.of("http error 403", "403: forbidden")),
            new Rule(ExtractionFailureCategory.CONTENT_UNAVAILABLE, List.of(
                    "video unavailable", "has been removed", "no longer available", "content isn't available",
                    "private video", "this video is private", "http error 404", "http error 410")),
            new Rule(ExtractionFailureCategory.TIMEOUT, List.of("timed out", "timeout")),
            new Rule(ExtractionFailureCategory.PROVIDER_ERROR, List.of("http error 5")),
            new Rule(ExtractionFailureCategory.NETWORK_ERROR, List.of(
                    "connection reset", "connection refused", "network is unreachable",
                    "temporary failure in name resolution", "name or service not known", "getaddrinfo failed",
                    "unable to download webpage")),
            new Rule(ExtractionFailureCategory.EXTRACTOR_ERROR, List.of(
                    "unable to extract", "traceback", "keyerror", "error: [")));

    private ExtractionFailureClassifier() {
    }

    public static ExtractionFailureCategory fromYtDlp(YtDlpFailedException e) {
        return fromYtDlp(e.stderr(), e.timedOut());
    }

    /** @param processTimedOut our own {@code ExternalProcess} bound fired — trumps whatever stderr says */
    public static ExtractionFailureCategory fromYtDlp(String stderr, boolean processTimedOut) {
        if (processTimedOut) {
            return ExtractionFailureCategory.TIMEOUT;
        }
        String haystack = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        for (Rule rule : YT_DLP_RULES) {
            if (rule.needles().stream().anyMatch(haystack::contains)) {
                return rule.category();
            }
        }
        return ExtractionFailureCategory.UNKNOWN;
    }

    /**
     * For an HTTP API provider (RapidAPI, the YouTube Data API). A 401 is
     * <em>our</em> credential failing, not a login wall on the content, so it
     * is a provider error rather than {@code AUTH_REQUIRED}.
     */
    public static ExtractionFailureCategory fromHttpStatus(int status) {
        if (status == 403) return ExtractionFailureCategory.HTTP_403;
        if (status == 429) return ExtractionFailureCategory.HTTP_429;
        if (status == 404 || status == 410) return ExtractionFailureCategory.CONTENT_UNAVAILABLE;
        if (status >= 400) return ExtractionFailureCategory.PROVIDER_ERROR;
        return ExtractionFailureCategory.UNKNOWN;
    }

    /**
     * For a thrown client-side exception. Walks the whole cause chain before
     * settling on a generic I/O failure, because Spring wraps a read timeout
     * in a {@code ResourceAccessException} and the timeout is the useful part.
     */
    public static ExtractionFailureCategory fromException(Throwable t) {
        boolean sawIo = false;
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) {
                return fromHttpStatus(response.getStatusCode().value());
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return ExtractionFailureCategory.TIMEOUT;
            }
            if (cause instanceof JacksonException) {
                return ExtractionFailureCategory.PROVIDER_ERROR;
            }
            sawIo |= cause instanceof IOException;
        }
        return sawIo ? ExtractionFailureCategory.NETWORK_ERROR : ExtractionFailureCategory.UNKNOWN;
    }
}
