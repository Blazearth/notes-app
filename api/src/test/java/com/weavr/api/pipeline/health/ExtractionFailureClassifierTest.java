package com.weavr.api.pipeline.health;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static com.weavr.api.pipeline.health.ExtractionFailureCategory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stderr samples are real yt-dlp wording — the bot-check line is the
 * exact message Render's datacenter IP gets on every YouTube probe
 * (CLAUDE.md, 8/8 URLs), and the Instagram one is its well-known block.
 */
class ExtractionFailureClassifierTest {

    private static ExtractionFailureCategory ytDlp(String stderr) {
        return ExtractionFailureClassifier.fromYtDlp(stderr, false);
    }

    @Test
    void recognisesYouTubesDatacenterBotCheck() {
        assertThat(ytDlp("ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot. "
                + "Use --cookies-from-browser or --cookies for the authentication."))
                .isEqualTo(BOT_CHECK);
    }

    /** Mentions both a rate limit and a login; behaves as a login wall, so it is one. */
    @Test
    void treatsInstagramsCombinedBlockAsAuthRequired() {
        assertThat(ytDlp("ERROR: [Instagram] DAbc123: Requested content is not available, "
                + "rate-limit reached or login required. Use --cookies"))
                .isEqualTo(AUTH_REQUIRED);
    }

    @Test
    void separatesRateLimitsFromForbidden() {
        assertThat(ytDlp("ERROR: [TikTok] 7123: Unable to download webpage: HTTP Error 429: Too Many Requests"))
                .isEqualTo(HTTP_429);
        assertThat(ytDlp("ERROR: [TikTok] 7123: Unable to download webpage: HTTP Error 403: Forbidden"))
                .isEqualTo(HTTP_403);
    }

    @Test
    void recognisesContentThatIsGoneNotBroken() {
        assertThat(ytDlp("ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader"))
                .isEqualTo(CONTENT_UNAVAILABLE);
        assertThat(ytDlp("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"))
                .isEqualTo(CONTENT_UNAVAILABLE);
        assertThat(ytDlp("ERROR: [generic] Unable to download webpage: HTTP Error 404: Not Found"))
                .isEqualTo(CONTENT_UNAVAILABLE);
    }

    @Test
    void recognisesUnsupportedAndAgeWalls() {
        assertThat(ytDlp("ERROR: Unsupported URL: https://example.com/article")).isEqualTo(UNSUPPORTED);
        assertThat(ytDlp("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate"))
                .isEqualTo(AUTH_REQUIRED);
    }

    @Test
    void separatesTimeoutsNetworkAndUpstreamServerErrors() {
        assertThat(ytDlp("ERROR: [TikTok] 7123: Unable to download webpage: The read operation timed out"))
                .isEqualTo(TIMEOUT);
        assertThat(ytDlp("ERROR: [TikTok] 7123: Unable to download webpage: [Errno 104] Connection reset by peer"))
                .isEqualTo(NETWORK_ERROR);
        assertThat(ytDlp("ERROR: Unable to download webpage: <urlopen error [Errno -3] "
                + "Temporary failure in name resolution>"))
                .isEqualTo(NETWORK_ERROR);
        assertThat(ytDlp("ERROR: [youtube] abc: Unable to download API page: HTTP Error 503: Service Unavailable"))
                .isEqualTo(PROVIDER_ERROR);
    }

    @Test
    void anUnrecognisedExtractorFailureIsAnExtractorError() {
        assertThat(ytDlp("ERROR: [TikTok] 7123: Unable to extract universal data for rehydration"))
                .isEqualTo(EXTRACTOR_ERROR);
        assertThat(ytDlp("ERROR: [instagram] abc: something new and strange happened")).isEqualTo(EXTRACTOR_ERROR);
    }

    @Test
    void anythingElseIsUnknownRatherThanForcedIntoABucket() {
        assertThat(ytDlp("")).isEqualTo(UNKNOWN);
        assertThat(ytDlp(null)).isEqualTo(UNKNOWN);
        assertThat(ytDlp("segmentation fault")).isEqualTo(UNKNOWN);
    }

    /** Our own process bound firing is a timeout whatever partial stderr says. */
    @Test
    void aProcessTimeoutWinsOverStderr() {
        assertThat(ExtractionFailureClassifier.fromYtDlp("ERROR: HTTP Error 429", true)).isEqualTo(TIMEOUT);
        assertThat(ExtractionFailureClassifier.fromYtDlp(new YtDlpFailedException("yt-dlp timed out", "")))
                .isEqualTo(TIMEOUT);
        assertThat(ExtractionFailureClassifier.fromYtDlp(new YtDlpFailedException("yt-dlp exited 1", "")))
                .isEqualTo(UNKNOWN);
    }

    @Test
    void mapsProviderHttpStatuses() {
        assertThat(ExtractionFailureClassifier.fromHttpStatus(403)).isEqualTo(HTTP_403);
        assertThat(ExtractionFailureClassifier.fromHttpStatus(429)).isEqualTo(HTTP_429);
        assertThat(ExtractionFailureClassifier.fromHttpStatus(404)).isEqualTo(CONTENT_UNAVAILABLE);
        assertThat(ExtractionFailureClassifier.fromHttpStatus(401)).isEqualTo(PROVIDER_ERROR);
        assertThat(ExtractionFailureClassifier.fromHttpStatus(400)).isEqualTo(PROVIDER_ERROR);
        assertThat(ExtractionFailureClassifier.fromHttpStatus(503)).isEqualTo(PROVIDER_ERROR);
    }

    @Test
    void findsTheUsefulCauseInsideSpringsWrappers() {
        assertThat(ExtractionFailureClassifier.fromException(
                new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out"))))
                .isEqualTo(TIMEOUT);
        assertThat(ExtractionFailureClassifier.fromException(
                new ResourceAccessException("I/O error", new IOException("wrapper", new SocketTimeoutException()))))
                .isEqualTo(TIMEOUT);
        assertThat(ExtractionFailureClassifier.fromException(
                new ResourceAccessException("I/O error", new ConnectException("refused"))))
                .isEqualTo(NETWORK_ERROR);
        assertThat(ExtractionFailureClassifier.fromException(
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)))
                .isEqualTo(HTTP_429);
        assertThat(ExtractionFailureClassifier.fromException(new IllegalStateException("?"))).isEqualTo(UNKNOWN);
    }
}
