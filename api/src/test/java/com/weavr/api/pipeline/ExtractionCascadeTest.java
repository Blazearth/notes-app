package com.weavr.api.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.audio.AsrTranscriber;
import com.weavr.api.pipeline.health.ExtractionAttempt;
import com.weavr.api.pipeline.health.ExtractionAttemptRecorder;
import com.weavr.api.pipeline.health.ExtractionFailureCategory;
import com.weavr.api.pipeline.ocr.VisualTextExtractor;
import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ordering decisions, without a network or a yt-dlp binary.
 *
 * <p>What each branch produces matters more than it looks: whichever source wins
 * is what the single Gemini call in Phase 3 sees, and a save that silently falls
 * back to a bare title would produce a confidently wrong extraction rather than
 * an honest failure.
 */
class ExtractionCascadeTest {

    private static final UUID SAVE_ID = UUID.randomUUID();

    private final YtDlpClient ytDlp = mock(YtDlpClient.class);
    private final RapidYtClient rapidYt = mock(RapidYtClient.class);
    private final YouTubeDataApiClient youtubeData = mock(YouTubeDataApiClient.class);
    private final AsrTranscriber asr = mock(AsrTranscriber.class);
    private final VisualTextExtractor visual = mock(VisualTextExtractor.class);
    private final LinkExtractor linkExtractor = mock(LinkExtractor.class);
    private final PdfExtractor pdfExtractor = mock(PdfExtractor.class);
    private final ExtractionAttemptRecorder attempts = new ExtractionAttemptRecorder();
    private final ExtractionCascade cascade = new ExtractionCascade(
            ytDlp, rapidYt, youtubeData, asr, visual, linkExtractor, pdfExtractor, attempts);

    {
        // The Data API is unconfigured unless a test says otherwise — the
        // pre-existing behaviour every test below was written against.
        when(youtubeData.fetch(anyString())).thenReturn(YouTubeDataApiClient.Outcome.notAttempted("not_configured"));
    }

    /** RapidAPI answers with this result; an empty one reads as a rate-limited failure. */
    private void stubRapid(Optional<RapidYtClient.ProbeResult> result) {
        when(rapidYt.probeDetailed(anyString())).thenReturn(result
                .map(RapidYtClient.ProbeOutcome::success)
                .orElseGet(() -> RapidYtClient.ProbeOutcome.failed(ExtractionFailureCategory.HTTP_429, 429)));
    }

    private static SourceMetadata metadata(String title, String description, List<String> autoCaptions) {
        return new SourceMetadata("vid1", title, description, "someone", 42.0,
                "https://example.com/thumb.jpg", List.of(), autoCaptions, null);
    }

    private static SourceMetadata metadataWithPinnedComment(
            String title, String description, List<String> autoCaptions, String pinnedComment) {
        return new SourceMetadata("vid1", title, description, "someone", 42.0,
                "https://example.com/thumb.jpg", List.of(), autoCaptions, pinnedComment);
    }

    @Test
    void prefersCaptionsAndKeepsMetadataAsContext() {
        when(ytDlp.probe(anyString()))
                .thenReturn(metadata("Miso ramen", "A quick weeknight bowl", List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("first brown the onions then add the stock and simmer"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text())
                .contains("Miso ramen")
                .contains("first brown the onions");
        verify(asr, never()).transcribe(anyString());
    }

    /**
     * The bug this covers: a video whose caption/description only summarises
     * ("garlic, butter, chili flakes...") while the creator's pinned comment
     * carries the real recipe with exact quantities and numbered steps. The
     * pinned comment must reach the model even though captions already won —
     * it is additive, not a fallback used only when captions are thin.
     */
    @Test
    void appendsPinnedCommentAlongsideWhicheverSourceWon() {
        when(ytDlp.probe(anyString())).thenReturn(metadataWithPinnedComment(
                "Cheesy Garlic Bread",
                "garlic, butter, chili flakes, dough, mozzarella",
                List.of("en"),
                "1½ cups maida, 25-30 garlic cloves, bake at 180C for 15-20 minutes"));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("today we're making extra cheesy garlic bread"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text())
                .contains("today we're making extra cheesy garlic bread")
                .contains("Pinned comment:")
                .contains("1½ cups maida")
                .contains("bake at 180C for 15-20 minutes");
    }

    /** The cheap path: a rich description means no caption fetch at all. */
    @Test
    void usesMetadataWhenNoCaptionsAreAdvertised() {
        when(ytDlp.probe(anyString())).thenReturn(metadata(
                "Sourdough starter guide",
                "Day 1: mix 50g flour with 50g water. Day 2: discard half and feed again.",
                List.of()));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
        verify(ytDlp, never()).fetchCaptions(anyString(), any(Path.class));
        verify(asr, never()).transcribe(anyString());
    }

    @Test
    void fallsBackToMetadataWhenAdvertisedCaptionsComeBackEmpty() {
        when(ytDlp.probe(anyString())).thenReturn(metadata(
                "Kyoto travel guide",
                "Twelve cafes worth the detour, with addresses and opening hours.",
                List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class))).thenReturn(Optional.empty());

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
    }

    /** Neither free source produced anything, so the last resort — ASR — runs. */
    @Test
    void fallsBackToAsrWhenCaptionsAndMetadataAreBothThin() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(
                Optional.of("first you brown the onions then add the stock and simmer for ten minutes"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("asr");
        assertThat(extraction.text()).contains("brown the onions");
    }

    /**
     * The overlay-text-only case, and the reason the visual tier exists: no
     * caption, no description, no voiceover — every free source above returns
     * nothing, and the payload is burned into the pixels.
     */
    @Test
    void fallsBackToTheVisualTierWhenEvenAsrComesUpEmpty() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(Optional.empty());
        when(visual.extract(anyString(), any(UUID.class))).thenReturn(Optional.of(
                new VisualTextExtractor.VisualText(
                        "400g rigatoni\n2 tbsp olive oil", "ocr", 4, 88.0, "clean")));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("ocr");
        assertThat(extraction.text()).contains("400g rigatoni");
        // The escalation rate is the number Phase 4 has to watch, and it is
        // invisible unless the winning step records how it did.
        assertThat(ExtractionCascade.stagePayload(extraction))
                .containsEntry("source", "ocr")
                .containsEntry("framesRead", 4);
    }

    /**
     * The tier is the only step that downloads video, so nothing above it may
     * pay that cost speculatively.
     */
    @Test
    void neverDownloadsVideoWhenACheaperSourceAlreadyWon() {
        when(ytDlp.probe(anyString()))
                .thenReturn(metadata("Miso ramen", "A quick weeknight bowl for two", List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("first brown the onions then add the stock and simmer"));

        cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        verify(visual, never()).extract(anyString(), any(UUID.class));
    }

    /**
     * OCR output skips the 40-character floor the text steps get: a short but
     * clean ingredient list is a perfectly good extraction, and the quality
     * gate has already judged it on confidence rather than length.
     */
    @Test
    void acceptsShortButCleanOcrTextThatWouldFailTheTextLengthFloor() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(Optional.empty());
        when(visual.extract(anyString(), any(UUID.class))).thenReturn(Optional.of(
                new VisualTextExtractor.VisualText("2 eggs\n1 cup flour", "ocr", 3, 90.0, "clean")));

        assertThat(cascade.extractFromUrl("https://example.com/v", SAVE_ID).text())
                .contains("1 cup flour");
    }

    /** The honest-failure case: not even the visual tier produced anything. */
    @Test
    void failsPermanentlyWhenNothingUsableExistsEvenAfterTheVisualTier() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(Optional.empty());
        when(visual.extract(anyString(), any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/v", SAVE_ID))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text_extracted");
    }

    /**
     * "Unsupported site" is not a final failure any more — it means "not a
     * video platform", so the cascade tries readable-text extraction instead
     * of giving up.
     */
    @Test
    void fallsBackToLinkExtractionWhenNoYtDlpExtractorMatches() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: Unsupported URL: https://example.com/article"));
        when(linkExtractor.extract(anyString())).thenReturn(Optional.of(
                new LinkExtractor.LinkExtraction("10 Rules for Writing Software",
                        "Make it work, make it right, make it fast. " +
                                "Premature optimization is the root of all evil in programming.")));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/article", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("link");
        assertThat(extraction.text()).contains("Premature optimization");
        assertThat(extraction.metadata().title()).isEqualTo("10 Rules for Writing Software");
        verify(asr, never()).transcribe(anyString());
    }

    @Test
    void failsPermanentlyWhenLinkExtractionAlsoFindsNothing() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: Unsupported URL: https://example.com/article"));
        when(linkExtractor.extract(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/article", SAVE_ID))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text_extracted");
    }

    /** A save whose URL is obviously a PDF skips yt-dlp entirely. */
    @Test
    void routesPdfUrlsStraightToThePdfExtractorWithoutProbing() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.of(
                "Section 1: Introduction. This paper presents a novel approach to widget design."));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/paper.pdf", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("pdf");
        assertThat(extraction.text()).contains("widget design");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void pdfDetectionIgnoresQueryStringAndFragment() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.of(
                "Section 1: Introduction. This paper presents a novel approach to widget design."));

        cascade.extractFromUrl("https://example.com/paper.pdf?utm_source=share#page=2", SAVE_ID);

        verify(pdfExtractor).extract("https://example.com/paper.pdf?utm_source=share#page=2");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void failsPermanentlyWhenThePdfHasNoExtractableText() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/scan.pdf", SAVE_ID))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text_extracted");
    }

    @Test
    void translatesRateLimitsIntoRetries() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/x", SAVE_ID))
                .isInstanceOf(RetryableJobException.class);
    }

    /**
     * A missing binary is a deployment fault, not the save's — retrying lets the
     * work survive a container rebuilt with yt-dlp installed. This is the path
     * that actually executes on a machine without it.
     */
    @Test
    void treatsAMissingBinaryAsRetryable() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new ProcessExecutionException("Could not run yt-dlp", new RuntimeException()));

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/x", SAVE_ID))
                .isInstanceOf(RetryableJobException.class)
                .hasMessageContaining("not runnable");
    }

    /** A failed caption fetch must not sink a save that metadata can carry. */
    @Test
    void survivesACaptionFetchThatThrows() {
        when(ytDlp.probe(anyString())).thenReturn(metadata(
                "Resistance band workout",
                "Three sets of twelve for each movement, ninety seconds rest between rounds.",
                List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1", "ERROR: subtitle fetch failed"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v", SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
    }

    // --- The RapidAPI branch (docs/extraction-architecture.md Phase 2) ---
    // None of the tests above touch it: every URL used so far is
    // https://example.com/..., which RapidYtClient.extractVideoId never
    // matches, so extractFromUrl's "if (RapidYtClient.extractVideoId(url) !=
    // null)" branch is never entered above. A YouTube-shaped URL is required.

    private static final String YOUTUBE_URL = "https://youtube.com/watch?v=dQw4w9WgXcQ";

    @Test
    void usesRapidApiTranscriptWhenPresentAndLongEnough() {
        SourceMetadata meta = metadata("Never Gonna Give You Up", "The official video", List.of("en"));
        stubRapid(Optional.of(new RapidYtClient.ProbeResult(
                meta, Optional.of("we're no strangers to love you know the rules and so do i"))));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text()).contains("strangers to love");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void usesRapidApiMetadataWhenTranscriptIsMissingButMetadataIsRich() {
        SourceMetadata meta = metadata("Kyoto travel guide",
                "Twelve cafes worth the detour, with addresses and opening hours.", List.of());
        stubRapid(Optional.of(
                new RapidYtClient.ProbeResult(meta, Optional.empty())));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
        verify(ytDlp, never()).probe(anyString());
    }

    /**
     * F5's decision: when RapidAPI succeeds but both its transcript and its
     * metadata are too thin, fail fast rather than let ASR/visual attempt a
     * yt-dlp-against-YouTube download that RapidAPI exists specifically to
     * avoid (and that is confirmed dead on Render — see CLAUDE.md).
     */
    @Test
    void failsFastWhenRapidApiSucceedsButEverythingIsTooThin() {
        SourceMetadata meta = metadata("Reel", "", List.of());
        stubRapid(Optional.of(
                new RapidYtClient.ProbeResult(meta, Optional.empty())));

        assertThatThrownBy(() -> cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("youtube_no_usable_text");

        verify(asr, never()).transcribe(anyString());
        verify(visual, never()).extract(anyString(), any(UUID.class));
        verify(ytDlp, never()).probe(anyString());
        verify(ytDlp, never()).fetchCaptions(anyString(), any(Path.class));
    }

    /**
     * The bug this covers: a real YouTube Short's transcript narrated
     * substitutions ("honey, used instead of maple syrup") with no
     * quantities, while its pinned comment carried the exact recipe card —
     * confirmed against a real video. RapidAPI's own response never carries
     * that comment, so before this, a YouTube save never saw it at all.
     */
    @Test
    void mergesASupplementalPinnedCommentIntoTheRapidApiTranscript() {
        SourceMetadata meta = metadata("Homemade Snickers Bar", "Try it or trash it", List.of("en"));
        stubRapid(Optional.of(new RapidYtClient.ProbeResult(
                meta, Optional.of("today we're making a snickers bar with honey instead of maple syrup"))));
        when(ytDlp.fetchPinnedComment(anyString()))
                .thenReturn(Optional.of("Recipe: 1/2 cup cashews, 2 tbsp honey, a pinch of salt"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text())
                .contains("honey instead of maple syrup")
                .contains("Pinned comment:", "1/2 cup cashews, 2 tbsp honey");
        assertThat(extraction.metadata().pinnedComment())
                .isEqualTo("Recipe: 1/2 cup cashews, 2 tbsp honey, a pinch of salt");
        verify(ytDlp, never()).probe(anyString());
    }

    /**
     * The common case on Render: RapidAPI succeeds, the supplemental yt-dlp
     * fetch fails (bot-blocked), and the save must still complete on
     * whatever RapidAPI already produced — no exception, no different
     * outcome from before this change existed.
     */
    @Test
    void rapidApiExtractionSurvivesAFailedSupplementalCommentFetch() {
        SourceMetadata meta = metadata("Never Gonna Give You Up", "The official video", List.of("en"));
        stubRapid(Optional.of(new RapidYtClient.ProbeResult(
                meta, Optional.of("we're no strangers to love you know the rules and so do i"))));
        when(ytDlp.fetchPinnedComment(anyString())).thenReturn(Optional.empty());

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text()).contains("strangers to love");
        assertThat(extraction.metadata().pinnedComment()).isNull();
    }

    /**
     * A pinned comment can rescue a save that RapidAPI's transcript and
     * description alone left too thin — the same merge point feeds both the
     * transcript branch above and this metadata-only branch.
     */
    @Test
    void aPinnedCommentCanRescueAnOtherwiseTooThinRapidApiResult() {
        SourceMetadata meta = metadata("Reel", "", List.of());
        stubRapid(Optional.of(
                new RapidYtClient.ProbeResult(meta, Optional.empty())));
        when(ytDlp.fetchPinnedComment(anyString())).thenReturn(Optional.of(
                "Full recipe: two cups flour, one cup sugar, three eggs, bake at 350F for 25 minutes"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
        assertThat(extraction.text()).contains("Full recipe:", "bake at 350F");
    }

    /**
     * "wait 3s or whatever, otherwise forward the pipeline" — a slow
     * supplemental fetch (the expected shape of a bot-check response on
     * Render, which doesn't fail instantly) must not hold up a save that
     * RapidAPI's transcript already made usable. The wait bound is
     * overridden to keep this test fast; the mechanism under test — give
     * up and move on rather than block — is the same either way.
     */
    @Test
    void givesUpOnASlowSupplementalCommentFetchAndForwardsThePipeline() throws Exception {
        SourceMetadata meta = metadata("Never Gonna Give You Up", "The official video", List.of("en"));
        stubRapid(Optional.of(new RapidYtClient.ProbeResult(
                meta, Optional.of("we're no strangers to love you know the rules and so do i"))));
        CountDownLatch fetchStarted = new CountDownLatch(1);
        when(ytDlp.fetchPinnedComment(anyString())).thenAnswer(invocation -> {
            fetchStarted.countDown();
            Thread.sleep(5000);
            return Optional.of("arrived too late to matter");
        });
        // Comfortably above thread-start scheduling jitter (so the fetch has
        // reliably begun and is genuinely mid-sleep when the wait expires)
        // and comfortably below the mock's 5s sleep (so the assertion below
        // is only satisfiable by giving up early, not by coincidence).
        cascade.setPinnedCommentWaitForTesting(Duration.ofMillis(300));

        long start = System.nanoTime();
        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(fetchStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(extraction.text()).contains("strangers to love");
        assertThat(extraction.metadata().pinnedComment()).isNull();
        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void fallsBackToYtDlpWhenRapidApiProbeIsEmpty() {
        stubRapid(Optional.empty());
        when(ytDlp.probe(anyString()))
                .thenReturn(metadata("Miso ramen", "A quick weeknight bowl", List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("first brown the onions then add the stock and simmer"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        verify(ytDlp).probe(anyString());
    }

    // --- Provider fallback: RapidAPI → YouTube Data API → yt-dlp ---

    private static final SourceMetadata RICH_DATA_API_METADATA = new SourceMetadata("dQw4w9WgXcQ",
            "One-pan lemon chicken",
            "4 chicken thighs, 1 lemon, 3 garlic cloves. Roast at 220C for 35 minutes, rest 5.",
            "Weeknight Kitchen", 253.0, "https://i.ytimg.com/vi/x/maxresdefault.jpg", List.of(), List.of(), null);

    private void stubDataApi(YouTubeDataApiClient.Outcome outcome) {
        when(youtubeData.fetch(anyString())).thenReturn(outcome);
    }

    private List<ExtractionAttempt> attemptsBy(String provider) {
        return attempts.recent().stream().filter(a -> a.provider().equals(provider)).toList();
    }

    @Test
    void rapidApiSuccessNeverTouchesTheDataApi() {
        stubRapid(Optional.of(new RapidYtClient.ProbeResult(
                metadata("Never Gonna Give You Up", "The official video", List.of("en")),
                Optional.of("we're no strangers to love you know the rules and so do i"))));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        verify(youtubeData, never()).fetch(anyString());
        assertThat(ExtractionCascade.stagePayload(extraction)).containsEntry("provider", "rapidapi");
        assertThat(attemptsBy("rapidapi")).singleElement()
                .satisfies(a -> assertThat(a.category()).isEqualTo(ExtractionFailureCategory.SUCCESS));
    }

    @Test
    void rapidApiFailsThenTheDataApiCarriesTheSave() {
        stubRapid(Optional.empty());
        stubDataApi(YouTubeDataApiClient.Outcome.success(RICH_DATA_API_METADATA));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("metadata");
        assertThat(extraction.text()).contains("Roast at 220C");
        assertThat(extraction.metadata().thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/x/maxresdefault.jpg");
        assertThat(ExtractionCascade.stagePayload(extraction)).containsEntry("provider", "youtube_data_api");
        verify(youtubeData).fetch("dQw4w9WgXcQ");
        // The bot-blocked path is never reached when the legitimate one worked.
        verify(ytDlp, never()).probe(anyString());

        assertThat(attemptsBy("rapidapi")).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(ExtractionFailureCategory.HTTP_429);
            assertThat(a.httpStatus()).isEqualTo(429);
            assertThat(a.platform()).isEqualTo("youtube");
        });
        assertThat(attemptsBy("youtube_data_api")).singleElement()
                .satisfies(a -> assertThat(a.success()).isTrue());
    }

    @Test
    void rapidApiFailsAndTheDataApiFailsSoYtDlpRunsAsBefore() {
        stubRapid(Optional.empty());
        stubDataApi(YouTubeDataApiClient.Outcome.failed(ExtractionFailureCategory.HTTP_429, 403, "quotaExceeded"));
        when(ytDlp.probe(anyString()))
                .thenReturn(metadata("Miso ramen", "A quick weeknight bowl", List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("first brown the onions then add the stock and simmer"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        assertThat(ExtractionCascade.stagePayload(extraction)).containsEntry("provider", "ytdlp");
        assertThat(attemptsBy("youtube_data_api")).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(ExtractionFailureCategory.HTTP_429);
            assertThat(a.detail()).isEqualTo("quotaExceeded");
        });
        assertThat(attemptsBy("ytdlp")).singleElement().satisfies(a -> assertThat(a.success()).isTrue());
    }

    /** Thin metadata is not a reason to stop: yt-dlp might still find captions (e.g. from a residential host). */
    @Test
    void thinDataApiMetadataFallsThroughToYtDlp() {
        stubRapid(Optional.empty());
        stubDataApi(YouTubeDataApiClient.Outcome.success(new SourceMetadata("dQw4w9WgXcQ", "Short",
                "#shorts #food", "Someone", 20.0, null, List.of(), List.of(), null)));
        when(ytDlp.probe(anyString()))
                .thenReturn(metadata("Miso ramen", "A quick weeknight bowl", List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("first brown the onions then add the stock and simmer"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(extraction.source()).isEqualTo("captions");
        verify(ytDlp).probe(anyString());
    }

    /**
     * Google saying "no such public video" is authoritative. Falling through
     * would turn a deleted video into a bot-check error on Render, which the
     * queue would then retry for hours.
     */
    @Test
    void theDataApiSayingTheVideoIsGoneEndsTheSaveHonestly() {
        stubRapid(Optional.empty());
        stubDataApi(YouTubeDataApiClient.Outcome.failed(
                ExtractionFailureCategory.CONTENT_UNAVAILABLE, 200, "no_such_video"));

        assertThatThrownBy(() -> cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("content_unavailable");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void anUnconfiguredDataApiIsNotRecordedAsAnAttempt() {
        stubRapid(Optional.empty());
        when(ytDlp.probe(anyString())).thenReturn(metadata("Sourdough starter guide",
                "Day 1: mix 50g flour with 50g water. Day 2: discard half and feed again.", List.of()));

        cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID);

        assertThat(attemptsBy("youtube_data_api")).isEmpty();
    }

    // --- Attempt records on the yt-dlp path ---

    @Test
    void recordsABotCheckAsBotCheckNotAsAGenericFailure() {
        stubRapid(Optional.empty());
        when(ytDlp.probe(anyString())).thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                "ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot."));

        assertThatThrownBy(() -> cascade.extractFromUrl(YOUTUBE_URL, SAVE_ID))
                .isInstanceOf(RetryableJobException.class);

        assertThat(attemptsBy("ytdlp")).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(ExtractionFailureCategory.BOT_CHECK);
            assertThat(a.detail()).isEqualTo("source_blocked");
            assertThat(a.platform()).isEqualTo("youtube");
        });
    }

    @Test
    void recordsInstagramAndTikTokAttemptsUnderTheirOwnPlatform() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel",
                "Full routine: 3 rounds of 12 squats, 10 push-ups, 30s plank, 60s rest between rounds.", List.of()));
        when(ytDlp.version()).thenReturn("2026.07.04");

        cascade.extractFromUrl("https://www.instagram.com/reel/DAbc123xyz/", SAVE_ID);
        cascade.extractFromUrl("https://www.tiktok.com/@someone/video/7123456789012345678", SAVE_ID);

        assertThat(attemptsBy("ytdlp")).extracting(ExtractionAttempt::platform)
                .containsExactly("instagram", "tiktok");
        assertThat(attemptsBy("ytdlp")).allSatisfy(a -> {
            assertThat(a.ytDlpVersion()).isEqualTo("2026.07.04");
            assertThat(a.success()).isTrue();
        });
        verify(rapidYt, never()).probeDetailed(anyString());
        verify(youtubeData, never()).fetch(anyString());
    }

    @Test
    void recordsAFailedCaptionFetchWithoutFailingTheSave() {
        when(ytDlp.probe(anyString())).thenReturn(metadata(
                "Resistance band workout",
                "Three sets of twelve for each movement, ninety seconds rest between rounds.",
                List.of("en")));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1", "ERROR: HTTP Error 429: Too Many Requests"));

        assertThat(cascade.extractFromUrl("https://example.com/v", SAVE_ID).source()).isEqualTo("metadata");
        assertThat(attemptsBy("ytdlp_captions")).singleElement()
                .satisfies(a -> assertThat(a.category()).isEqualTo(ExtractionFailureCategory.HTTP_429));
    }

    /** Records must never carry the URL, a key or stderr — only the declared fields. */
    @Test
    void attemptRecordsCarryNoUrlOrRawStderr() {
        when(ytDlp.probe(anyString())).thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                "ERROR: [instagram] secret-path: HTTP Error 403: Forbidden --cookies /tmp/yt-dlp-cookies-123.txt"));

        assertThatThrownBy(() -> cascade.extractFromUrl("https://www.instagram.com/reel/DAbc123xyz/?igsh=tok", SAVE_ID));

        assertThat(attempts.recent()).singleElement().satisfies(a ->
                assertThat(a.toString()).doesNotContain("igsh", "cookies", "secret-path", "DAbc123xyz"));
    }
}
