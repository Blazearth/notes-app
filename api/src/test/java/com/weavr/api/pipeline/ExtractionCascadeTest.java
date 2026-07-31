package com.weavr.api.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.audio.AsrTranscriber;
import com.weavr.api.pipeline.ocr.VisualTextExtractor;
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
    private final AsrTranscriber asr = mock(AsrTranscriber.class);
    private final VisualTextExtractor visual = mock(VisualTextExtractor.class);
    private final LinkExtractor linkExtractor = mock(LinkExtractor.class);
    private final PdfExtractor pdfExtractor = mock(PdfExtractor.class);
    private final ExtractionCascade cascade =
            new ExtractionCascade(ytDlp, asr, visual, linkExtractor, pdfExtractor);

    private static SourceMetadata metadata(String title, String description, List<String> autoCaptions) {
        return new SourceMetadata("vid1", title, description, "someone", 42.0,
                "https://example.com/thumb.jpg", List.of(), autoCaptions);
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
}
