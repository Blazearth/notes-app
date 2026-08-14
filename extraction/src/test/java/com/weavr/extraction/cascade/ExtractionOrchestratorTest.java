package com.weavr.extraction.cascade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.weavr.extraction.artifact.ArtifactStore;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import com.weavr.extraction.link.LinkExtractor;
import com.weavr.extraction.ocr.OcrProperties;
import com.weavr.extraction.ocr.VisualExtractor;
import com.weavr.extraction.pdf.PdfExtractor;
import com.weavr.extraction.ytdlp.RapidYtClient;
import com.weavr.extraction.ytdlp.SourceMetadata;
import com.weavr.extraction.ytdlp.YtDlpClient;
import com.weavr.extraction.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The service's own version of {@code api}'s {@code ExtractionCascadeTest} —
 * exercises {@link ExtractionOrchestrator}, the class {@code POST /extract}
 * actually calls. Pins two things Phase 3's own gate names explicitly: every
 * cascade branch resolves to the right wire outcome, and nothing here ever
 * reports success with text below the usability threshold and no artifact
 * flag to explain why (docs/extraction-architecture.md Part D: "a partial
 * extraction is never reported as success").
 */
@ExtendWith(MockitoExtension.class)
class ExtractionOrchestratorTest {

    private static final OcrProperties OCR_PROPERTIES = new OcrProperties(
            true, "tesseract", "eng", 6, Duration.ofSeconds(30), 90, 12,
            0.25, 150, "scale=iw*2:-2:flags=lanczos,format=gray", 60, 8, 0.5, 2, true, 4);

    @Mock YtDlpClient ytDlp;
    @Mock RapidYtClient rapidYt;
    @Mock LinkExtractor linkExtractor;
    @Mock PdfExtractor pdfExtractor;
    @Mock VisualExtractor visual;
    @Mock ArtifactStore artifacts;

    private ExtractionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ExtractionOrchestrator(ytDlp, rapidYt, linkExtractor, pdfExtractor, visual, artifacts, OCR_PROPERTIES);
    }

    private static SourceMetadata metadata(String title, String description, boolean hasCaptions) {
        return new SourceMetadata("vid123", title, description, "uploader", 42.0, "https://thumb",
                hasCaptions ? List.of("en") : List.of(), List.of(), null);
    }

    // --- captions / metadata (non-YouTube, via yt-dlp) -----------------------

    @Test
    void captionsAboveThresholdWin() throws Exception {
        when(ytDlp.probe(anyString())).thenReturn(metadata("A great recipe", "short", true));
        when(ytDlp.fetchCaptions(anyString(), any(Path.class)))
                .thenReturn(Optional.of("x".repeat(80)));

        ExtractionResult result = orchestrator.extract("https://example.com/reel", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("captions");
        assertThat(result.text()).contains("A great recipe").contains("x".repeat(80));
        assertThat(result.needsTranscription()).isFalse();
        assertThat(result.needsVisionEscalation()).isFalse();
    }

    @Test
    void fallsBackToMetadataWhenNoCaptions() {
        when(ytDlp.probe(anyString())).thenReturn(
                metadata("A great recipe", "d".repeat(60), false));

        ExtractionResult result = orchestrator.extract("https://example.com/reel", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("metadata");
        assertThat(result.text()).contains("d".repeat(60));
    }

    // --- YouTube via RapidAPI --------------------------------------------------

    @Test
    void rapidApiTranscriptWinsWithoutTouchingYtDlp() {
        SourceMetadata meta = metadata("Rick Astley", "official video", false);
        when(rapidYt.probe(anyString()))
                .thenReturn(Optional.of(new RapidYtClient.ProbeResult(meta, Optional.of("n".repeat(80)))));

        ExtractionResult result = orchestrator.extract(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("captions");
        assertThat(result.metadata().platform()).isEqualTo("youtube");
        verifyNoInteractions(ytDlp);
    }

    @Test
    void rapidApiThinContentFailsFastRatherThanFallingThroughToYtDlp() {
        SourceMetadata meta = metadata("x", "y", false); // both far under the threshold
        when(rapidYt.probe(anyString()))
                .thenReturn(Optional.of(new RapidYtClient.ProbeResult(meta, Optional.empty())));

        assertThatThrownBy(() -> orchestrator.extract(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", ExtractionOptions.defaults()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE));

        // F5, preserved: no ASR/video download aimed at YouTube directly.
        verifyNoInteractions(ytDlp);
    }

    @Test
    void rapidApiEmptyFallsBackToYtDlp() {
        when(rapidYt.probe(anyString())).thenReturn(Optional.empty());
        when(ytDlp.probe(anyString())).thenReturn(metadata("Title", "d".repeat(60), false));

        ExtractionResult result = orchestrator.extract(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("metadata");
    }

    // --- unsupported source -> link fallback -----------------------------------

    @Test
    void unsupportedSourceFallsThroughToAReadableLink() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1", "ERROR: Unsupported URL: https://example.com/article"));
        when(linkExtractor.extract(anyString()))
                .thenReturn(Optional.of(new LinkExtractor.LinkExtraction("An Article", "p".repeat(80))));

        ExtractionResult result = orchestrator.extract("https://example.com/article", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("link");
        assertThat(result.text()).isEqualTo("p".repeat(80));
    }

    @Test
    void unsupportedSourceAndUnreadableLinkIsUnsupportedUrl() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1", "ERROR: Unsupported URL: https://example.com/article"));
        when(linkExtractor.extract(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/article", ExtractionOptions.defaults()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.UNSUPPORTED_URL));
    }

    @Test
    void aPermanentYtDlpFailureIsNotRetried() {
        when(ytDlp.probe(anyString()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1", "ERROR: Video unavailable"));

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/gone", ExtractionOptions.defaults()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> {
                    assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE);
                    assertThat(((ExtractionException) e).code().retryable()).isFalse();
                });
    }

    // --- PDF --------------------------------------------------------------------

    @Test
    void pdfWithReadableTextSucceeds() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.of("p".repeat(80)));

        ExtractionResult result = orchestrator.extract("https://example.com/doc.pdf", ExtractionOptions.defaults());

        assertThat(result.source()).isEqualTo("pdf");
        verifyNoInteractions(ytDlp, rapidYt);
    }

    @Test
    void pdfWithNoTextIsContentUnavailable() {
        when(pdfExtractor.extract(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/doc.pdf", ExtractionOptions.defaults()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE));
    }

    // --- no-partial-success -------------------------------------------------------

    @Test
    void thinContentWithNoOptionsRequestedIsNeverReportedAsSuccess() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false)); // under threshold

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/thin", ExtractionOptions.defaults()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE));
    }

    // --- audio artifact -----------------------------------------------------------

    @Test
    void thinContentWithAudioRequestedReturnsATranscriptionArtifact() throws IOException {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false));
        Path audioFile = Files.createTempFile("test-audio-", ".m4a");
        Files.writeString(audioFile, "fake-audio-bytes");
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt())).thenReturn(Optional.of(audioFile));
        ArtifactStore.StoredArtifact stored = new ArtifactStore.StoredArtifact(
                "audio", "sig:abc.123.def", 17, Instant.now().plusSeconds(600));
        when(artifacts.put(eq("audio"), any(byte[].class))).thenReturn(stored);

        ExtractionResult result = orchestrator.extract("https://example.com/reel",
                new ExtractionOptions(true, false, null));

        assertThat(result.needsTranscription()).isTrue();
        assertThat(result.needsVisionEscalation()).isFalse();
        assertThat(result.artifacts()).containsExactly(stored);
        Files.deleteIfExists(audioFile);
    }

    @Test
    void audioRequestedButUnavailableFallsThroughRatherThanReportingSuccess() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false));
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/reel",
                new ExtractionOptions(true, false, null)))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE));
        verify(artifacts, never()).put(anyString(), any(byte[].class));
    }

    // --- visual tier ---------------------------------------------------------------

    @Test
    void framesPassingTheGateReturnTextDirectly() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false));
        when(visual.extract(anyString(), anyInt())).thenReturn(Optional.of(
                new VisualExtractor.VisualText("1 cup flour\n2 eggs", "ocr", 4, 91.2,
                        "8 words at mean confidence 91.2 across 4 frames", false, List.of())));

        ExtractionResult result = orchestrator.extract("https://example.com/reel",
                new ExtractionOptions(false, true, null));

        assertThat(result.source()).isEqualTo("ocr");
        assertThat(result.text()).isEqualTo("1 cup flour\n2 eggs");
        assertThat(result.needsVisionEscalation()).isFalse();
    }

    @Test
    void framesFailingTheGateReturnArtifactsAndAnEscalationFlag() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false));
        ArtifactStore.StoredArtifact frame = new ArtifactStore.StoredArtifact(
                "frame", "sig:frame.123.abc", 4096, Instant.now().plusSeconds(600));
        when(visual.extract(anyString(), anyInt())).thenReturn(Optional.of(
                new VisualExtractor.VisualText("garbled |][ soup", "ocr-below-floor", 3, 22.0,
                        "only 40% of tokens contain a letter, floor is 50%", true, List.of(frame))));

        ExtractionResult result = orchestrator.extract("https://example.com/reel",
                new ExtractionOptions(false, true, null));

        assertThat(result.needsVisionEscalation()).isTrue();
        assertThat(result.artifacts()).containsExactly(frame);
    }

    @Test
    void framesRequestedButNothingUsableFallsThroughToContentUnavailable() {
        when(ytDlp.probe(anyString())).thenReturn(metadata("x", "y", false));
        when(visual.extract(anyString(), anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.extract("https://example.com/reel",
                new ExtractionOptions(false, true, null)))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.CONTENT_UNAVAILABLE));
    }
}
