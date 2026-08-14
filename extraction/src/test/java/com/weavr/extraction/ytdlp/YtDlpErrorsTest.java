package com.weavr.extraction.ytdlp;

import com.weavr.extraction.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire-code mapping table this class exists to promote to the
 * process boundary (docs/extraction-architecture.md Part D's error table's
 * own "Maps from / raised when" column).
 */
class YtDlpErrorsTest {

    @Test
    void unsupportedSourceMapsToUnsupportedUrl() {
        assertThat(YtDlpErrors.classify("ERROR: Unsupported URL: https://example.com").code())
                .isEqualTo(ErrorCode.UNSUPPORTED_URL);
    }

    @Test
    void deletedVideoMapsToContentUnavailable() {
        assertThat(YtDlpErrors.classify("ERROR: [youtube] abc123: Video unavailable").code())
                .isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
    }

    @Test
    void privateVideoMapsToContentUnavailable() {
        assertThat(YtDlpErrors.classify("ERROR: This video is private").code())
                .isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
    }

    @Test
    void ageRestrictedMapsToContentUnavailable() {
        assertThat(YtDlpErrors.classify("ERROR: Sign in to confirm your age").code())
                .isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
    }

    @Test
    void paywalledMapsToContentUnavailable() {
        assertThat(YtDlpErrors.classify("ERROR: Join this channel to get access to members-only content")
                .code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
    }

    @Test
    void malformedUrlMapsToInvalidUrl() {
        assertThat(YtDlpErrors.classify("ERROR: is not a valid URL").code())
                .isEqualTo(ErrorCode.INVALID_URL);
    }

    @Test
    void botCheckMapsToTemporaryProviderError() {
        assertThat(YtDlpErrors.classify("ERROR: Sign in to confirm you're not a bot").code())
                .isEqualTo(ErrorCode.TEMPORARY_PROVIDER_ERROR);
    }

    @Test
    void rateLimitMapsToTemporaryProviderError() {
        assertThat(YtDlpErrors.classify("ERROR: HTTP Error 429: Too Many Requests").code())
                .isEqualTo(ErrorCode.TEMPORARY_PROVIDER_ERROR);
    }

    @Test
    void connectionResetMapsToNetworkError() {
        assertThat(YtDlpErrors.classify("ERROR: unable to download video data: Connection reset by peer")
                .code()).isEqualTo(ErrorCode.NETWORK_ERROR);
    }

    @Test
    void unrecognisedStderrMapsToExtractionFailedAndIsRetryable() {
        YtDlpErrors.Classification classification = YtDlpErrors.classify("ERROR: something entirely new broke");

        assertThat(classification.code()).isEqualTo(ErrorCode.EXTRACTION_FAILED);
        assertThat(classification.code().retryable()).isTrue();
    }

    @Test
    void toExceptionCarriesTheMappedCodeAndAUserSafeMessage() {
        YtDlpFailedException failure = new YtDlpFailedException("yt-dlp exited 1",
                "ERROR: Video unavailable");

        var exception = YtDlpErrors.toException(failure);

        assertThat(exception.code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
        assertThat(exception.userMessage()).doesNotContain("yt-dlp").doesNotContain("stderr");
    }
}
