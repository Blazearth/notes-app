package com.weavr.api.pipeline.ytdlp;

import java.util.List;
import java.util.Locale;

/**
 * Maps yt-dlp's stderr onto something a user can read, and decides whether
 * retrying could ever help.
 *
 * <p>This classification is what stops the queue wasting an hour of backoff on a
 * deleted Reel, and — more importantly — it is the only explanation the user
 * ever gets. Silent capture means nobody is watching when a save fails, so the
 * feed's error line is the entire failure experience.
 *
 * <p>Extractors break when platforms change; that is routine, not exceptional.
 * Anything unrecognised is therefore treated as <em>retryable</em>, so a
 * transient breakage recovers on its own once the container updates yt-dlp.
 */
public final class YtDlpErrors {

    /**
     * @param permanent   true when retrying cannot help
     * @param errorCode   stable key, safe to branch on
     * @param userMessage shown in the feed; no internals
     */
    public record Classification(boolean permanent, String errorCode, String userMessage) {
    }

    private record Rule(List<String> needles, String errorCode, String userMessage) {
    }

    /** Order matters: the first match wins. */
    private static final List<Rule> PERMANENT = List.of(
            new Rule(List.of("unsupported url", "no suitable extractor"),
                    "unsupported_source",
                    "Weavr can't read links from this site yet."),
            new Rule(List.of("video unavailable", "this video has been removed", "content isn't available",
                            "video has been removed", "no longer available"),
                    "content_unavailable",
                    "That post isn't available any more."),
            new Rule(List.of("private video", "this video is private", "requested content is not available"),
                    "content_private",
                    "That post is private, so Weavr can't open it."),
            new Rule(List.of("sign in to confirm your age", "age-restricted", "age restricted"),
                    "age_restricted",
                    "That post is age-restricted and can't be saved."),
            new Rule(List.of("members-only", "join this channel", "requires payment", "paid content"),
                    "content_paywalled",
                    "That post is behind a paywall."),
            new Rule(List.of("is not a valid url", "unable to parse"),
                    "bad_url",
                    "That doesn't look like a link Weavr can open."));

    /**
     * Retryable, but worth naming so the log says what happened. Login walls sit
     * here deliberately: Instagram increasingly requires authentication, and
     * using a personal account's cookies risks a ban and sits badly against the
     * project's ToS posture — so unauthenticated failure is an acceptable
     * outcome that may simply succeed later from a different IP.
     */
    private static final List<Rule> RETRYABLE = List.of(
            new Rule(List.of("sign in to confirm you're not a bot", "login required", "requires login",
                            "rate-limit", "rate limit", "http error 429", "too many requests"),
                    "source_blocked",
                    "That site is blocking Weavr right now. We'll try again."),
            new Rule(List.of("http error 5", "temporary failure", "connection reset", "timed out",
                            "network is unreachable", "unable to download"),
                    "source_unreachable",
                    "We couldn't reach that link. We'll try again."));

    private YtDlpErrors() {
    }

    public static Classification classify(String stderr) {
        String haystack = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);

        for (Rule rule : PERMANENT) {
            if (matches(haystack, rule)) {
                return new Classification(true, rule.errorCode(), rule.userMessage());
            }
        }
        for (Rule rule : RETRYABLE) {
            if (matches(haystack, rule)) {
                return new Classification(false, rule.errorCode(), rule.userMessage());
            }
        }

        // Unknown: assume the extractor broke and will be fixed, rather than
        // permanently failing a save we might well handle tomorrow.
        return new Classification(false, "extract_failed",
                "We couldn't read that link. We'll try again.");
    }

    private static boolean matches(String haystack, Rule rule) {
        return rule.needles().stream().anyMatch(haystack::contains);
    }
}
