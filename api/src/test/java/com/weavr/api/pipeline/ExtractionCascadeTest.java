package com.weavr.api.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.audio.AsrTranscriber;
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

    private final YtDlpClient ytDlp = mock(YtDlpClient.class);
    private final AsrTranscriber asr = mock(AsrTranscriber.class);
    private final LinkExtractor linkExtractor = mock(LinkExtractor.class);
    private final PdfExtractor pdfExtractor = mock(PdfExtractor.class);
    private final ExtractionCascade cascade =
            new ExtractionCascade(ytDlp, asr, linkExtractor, pdfExtractor);

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

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v");

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

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v");

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

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v");

        assertThat(extraction.source()).isEqualTo("metadata");
    }

    /** Neither free source produced anything, so the last resort — ASR — runs. */
    @Test
    void fallsBackToAsrWhenCaptionsAndMetadataAreBothThin() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(
                Optional.of("first you brown the onions then add the stock and simmer for ten minutes"));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v");

        assertThat(extraction.source()).isEqualTo("asr");
        assertThat(extraction.text()).contains("brown the onions");
    }

    /** The honest-failure case: not even ASR produced anything usable. */
    @Test
    void failsPermanentlyWhenNothingUsableExistsEvenAfterAsr() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("Reel", "", List.of()));
        when(asr.transcribe(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/v"))
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

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/article");

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

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/article"))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text_extracted");
    }

    /** A save whose URL is obviously a PDF skips yt-dlp entirely. */
    @Test
    void routesPdfUrlsStraightToThePdfExtractorWithoutProbing() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.of(
                "Section 1: Introduction. This paper presents a novel approach to widget design."));

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/paper.pdf");

        assertThat(extraction.source()).isEqualTo("pdf");
        assertThat(extraction.text()).contains("widget design");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void pdfDetectionIgnoresQueryStringAndFragment() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.of(
                "Section 1: Introduction. This paper presents a novel approach to widget design."));

        cascade.extractFromUrl("https://example.com/paper.pdf?utm_source=share#page=2");

        verify(pdfExtractor).extract("https://example.com/paper.pdf?utm_source=share#page=2");
        verify(ytDlp, never()).probe(anyString());
    }

    @Test
    void failsPermanentlyWhenThePdfHasNoExtractableText() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/scan.pdf"))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text_extracted");
    }

    @Test
    void translatesRateLimitsIntoRetries() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/x"))
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

        assertThatThrownBy(() -> cascade.extractFromUrl("https://example.com/x"))
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

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl("https://example.com/v");

        assertThat(extraction.source()).isEqualTo("metadata");
    }
}
