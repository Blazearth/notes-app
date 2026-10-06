package com.weavr.api.pipeline.health;

import java.util.List;
import java.util.Optional;

import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionCanaryTest {

    private static final String YOUTUBE = "https://www.youtube.com/watch?v=jNQXAC9IVRw";
    private static final String TIKTOK = "https://www.tiktok.com/@scout2015/video/6718335390845095173";

    private final YtDlpClient ytDlp = mock(YtDlpClient.class);
    private final RapidYtClient rapidYt = mock(RapidYtClient.class);
    private final YouTubeDataApiClient youtubeData = mock(YouTubeDataApiClient.class);
    private final ExtractionAttemptRecorder recorder = new ExtractionAttemptRecorder();

    private static SourceMetadata meta(String title, String description) {
        return new SourceMetadata("id", title, description, "someone", 19.0, null, List.of(), List.of(), null);
    }

    private ExtractionCanary canary(String... targets) {
        return new ExtractionCanary(List.of(targets), ytDlp, rapidYt, youtubeData, recorder);
    }

    @Test
    void checksEveryConfiguredYoutubeProviderAndRecordsEachResult() {
        when(rapidYt.enabled()).thenReturn(true);
        when(youtubeData.enabled()).thenReturn(true);
        when(rapidYt.probeDetailed(anyString())).thenReturn(RapidYtClient.ProbeOutcome.success(
                new RapidYtClient.ProbeResult(meta("Me at the zoo", "the elephants"), Optional.empty())));
        when(youtubeData.fetch("jNQXAC9IVRw")).thenReturn(
                YouTubeDataApiClient.Outcome.success(meta("Me at the zoo", "the elephants")));
        when(ytDlp.probe(anyString())).thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                "ERROR: [youtube] jNQXAC9IVRw: Sign in to confirm you're not a bot."));
        when(ytDlp.version()).thenReturn("2026.07.04");

        List<ExtractionAttempt> results = canary(YOUTUBE).run();

        assertThat(results).extracting(ExtractionAttempt::provider)
                .containsExactly("rapidapi", "youtube_data_api", "ytdlp");
        assertThat(results).allSatisfy(a -> {
            assertThat(a.context()).isEqualTo("canary");
            assertThat(a.platform()).isEqualTo("youtube");
        });
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();
        assertThat(results.get(2).category()).isEqualTo(ExtractionFailureCategory.BOT_CHECK);
        assertThat(results.get(2).ytDlpVersion()).isEqualTo("2026.07.04");
        assertThat(recorder.recent()).isEqualTo(results);
    }

    @Test
    void skipsUnconfiguredProvidersAndNonYoutubeTargetsOnlyUseYtDlp() {
        when(ytDlp.probe(anyString())).thenReturn(meta("scout", "a dog video"));

        List<ExtractionAttempt> results = canary(YOUTUBE, TIKTOK).run();

        assertThat(results).extracting(ExtractionAttempt::provider).containsExactly("ytdlp", "ytdlp");
        assertThat(results).extracting(ExtractionAttempt::platform).containsExactly("youtube", "tiktok");
        verify(rapidYt, never()).probeDetailed(anyString());
        verify(youtubeData, never()).fetch(anyString());
    }

    /** A probe that returns nothing at all is a silent extractor breakage, not a pass. */
    @Test
    void emptyMetadataCountsAsAnExtractorFailure() {
        when(ytDlp.probe(anyString())).thenReturn(meta(null, null));

        ExtractionAttempt result = canary(TIKTOK).run().getFirst();

        assertThat(result.success()).isFalse();
        assertThat(result.category()).isEqualTo(ExtractionFailureCategory.EXTRACTOR_ERROR);
        assertThat(result.detail()).isEqualTo("empty_metadata");
    }

    @Test
    void aMissingTitleAloneIsNotABreakage() {
        when(ytDlp.probe(anyString())).thenReturn(meta(null, "a caption with the actual content"));

        assertThat(canary(TIKTOK).run().getFirst().success()).isTrue();
    }

    @Test
    void oneBrokenTargetDoesNotStopTheRest() {
        when(ytDlp.probe(TIKTOK)).thenThrow(new IllegalStateException("unexpected"));
        when(ytDlp.probe("https://www.instagram.com/p/aye83DjauH/")).thenReturn(meta("naomi", "a post"));

        List<ExtractionAttempt> results = canary(TIKTOK, "https://www.instagram.com/p/aye83DjauH/").run();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).category()).isEqualTo(ExtractionFailureCategory.UNKNOWN);
        assertThat(results.get(1).success()).isTrue();
    }

    @Test
    void theScheduledEntryPointNeverThrows() {
        when(ytDlp.probe(anyString())).thenThrow(new RuntimeException("boom"));

        canary(TIKTOK).scheduledRun();

        assertThat(recorder.recent()).hasSize(1);
    }
}
