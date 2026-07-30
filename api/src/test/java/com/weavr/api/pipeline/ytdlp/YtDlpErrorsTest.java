package com.weavr.api.pipeline.ytdlp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These strings are real yt-dlp stderr. Classifying them wrongly is expensive in
 * both directions: a permanent error retried five times wastes an hour of
 * backoff, and a transient one failed immediately loses a save that would have
 * worked on the next attempt.
 */
class YtDlpErrorsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ERROR: Unsupported URL: https://example.com/watch",
            "ERROR: [generic] None: no suitable extractor found",
    })
    void unsupportedSitesArePermanent(String stderr) {
        YtDlpErrors.Classification result = YtDlpErrors.classify(stderr);

        assertThat(result.permanent()).isTrue();
        assertThat(result.errorCode()).isEqualTo("unsupported_source");
        assertThat(result.userMessage()).doesNotContain("ERROR").doesNotContain("http");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ERROR: [youtube] dQw4w9WgXcQ: Video unavailable",
            "ERROR: This video has been removed by the uploader",
            "ERROR: [instagram] Requested content is not available, rate-limit reached",
    })
    void goneOrPrivateContentIsPermanent(String stderr) {
        assertThat(YtDlpErrors.classify(stderr).permanent()).isTrue();
    }

    @Test
    void ageRestrictionIsPermanentAndExplained() {
        YtDlpErrors.Classification result = YtDlpErrors.classify(
                "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.");

        assertThat(result.permanent()).isTrue();
        assertThat(result.errorCode()).isEqualTo("age_restricted");
        assertThat(result.userMessage()).isEqualTo("That post is age-restricted and can't be saved.");
    }

    /**
     * Bot checks and rate limits look permanent but are not — a datacenter IP
     * gets blocked far more aggressively than a residential one, and the same
     * URL often works minutes later.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ERROR: [youtube] abc: Sign in to confirm you're not a bot.",
            "ERROR: Unable to download webpage: HTTP Error 429: Too Many Requests",
    })
    void blocksAndRateLimitsAreRetryable(String stderr) {
        YtDlpErrors.Classification result = YtDlpErrors.classify(stderr);

        assertThat(result.permanent()).isFalse();
        assertThat(result.errorCode()).isEqualTo("source_blocked");
    }

    @Test
    void serverErrorsAreRetryable() {
        YtDlpErrors.Classification result = YtDlpErrors.classify(
                "ERROR: Unable to download webpage: HTTP Error 503: Service Unavailable");

        assertThat(result.permanent()).isFalse();
    }

    /**
     * Extractors break whenever a platform changes its markup. That is routine,
     * so an unrecognised failure must not permanently kill a save that will work
     * again once the container updates yt-dlp.
     */
    @Test
    void unrecognisedFailuresDefaultToRetryable() {
        YtDlpErrors.Classification result = YtDlpErrors.classify(
                "ERROR: [tiktok] Unable to extract sigi state; please report this issue");

        assertThat(result.permanent()).isFalse();
        assertThat(result.errorCode()).isEqualTo("extract_failed");
    }

    @Test
    void handlesNullAndEmptyStderr() {
        assertThat(YtDlpErrors.classify(null).permanent()).isFalse();
        assertThat(YtDlpErrors.classify("").errorCode()).isEqualTo("extract_failed");
    }

    /** Messages reach the feed, so they must never leak a URL or a stack trace. */
    @Test
    void userMessagesStayFreeOfInternals() {
        String[] samples = {
                "ERROR: Unsupported URL: https://internal.example.com/x?token=secret",
                "ERROR: Private video. Sign in if you've been granted access to this video",
                "ERROR: Unable to download webpage: HTTP Error 500",
                "something nobody has ever seen",
        };

        for (String stderr : samples) {
            String message = YtDlpErrors.classify(stderr).userMessage();
            assertThat(message)
                    .doesNotContain("http")
                    .doesNotContain("ERROR")
                    .doesNotContain("yt-dlp")
                    .isNotBlank();
        }
    }
}
