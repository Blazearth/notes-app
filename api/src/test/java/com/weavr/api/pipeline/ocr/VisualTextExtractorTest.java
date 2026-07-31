package com.weavr.api.pipeline.ocr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.gemini.GeminiClient;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.job.RetryAfterException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The escalation decisions — which tier's output wins, and when a save is
 * allowed to spend a vision request.
 *
 * <p>These are the choices that decide whether the Flash pool gets burned or
 * invented ingredients reach a user, and neither failure is visible in
 * production, so each branch is pinned explicitly.
 */
class VisualTextExtractorTest {

    private static final UUID SAVE_ID = UUID.randomUUID();
    private static final String URL = "https://example.com/reel";

    private static final String GOOD_OCR =
            "CREAMY TOMATO PASTA\n400g rigatoni\n2 tbsp olive oil\n1 cup heavy cream";

    private final YtDlpClient ytDlp = mock(YtDlpClient.class);
    private final FrameExtractor frames = mock(FrameExtractor.class);
    private final TesseractClient tesseract = mock(TesseractClient.class);
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final GeminiBudgetService budget = mock(GeminiBudgetService.class);

    private VisualTextExtractor extractor;
    private Path colourFrame;

    private static OcrProperties properties(boolean enabled, boolean visionEscalation) {
        return new OcrProperties(enabled, "tesseract", "eng", 6, Duration.ofSeconds(30),
                90, 12, 0.25, 150, null, 60.0, 8, 0.5, 2, visionEscalation, 4);
    }

    private static GeminiProperties geminiProperties() {
        return new GeminiProperties("key", "flash-lite", "flash", 500, 20, 0.7,
                Duration.ofSeconds(30));
    }

    private VisualTextExtractor build(OcrProperties props) {
        return new VisualTextExtractor(ytDlp, frames, tesseract, new FrameTextVoter(props),
                new OcrQualityGate(props), new ThumbnailSelector(), gemini, budget,
                geminiProperties(), props);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        colourFrame = dir.resolve("colour-001.jpg");
        Files.write(colourFrame, new byte[]{1, 2, 3});
        Path ocrFrame = dir.resolve("ocr-001.png");
        Files.write(ocrFrame, new byte[]{4, 5, 6});

        when(ytDlp.downloadVideo(anyString(), any(Path.class), anyInt()))
                .thenReturn(Optional.of(dir.resolve("v.mp4")));
        when(frames.extract(any(Path.class), any(Path.class)))
                .thenReturn(new FrameExtractor.Keyframes(List.of(colourFrame), List.of(ocrFrame)));

        extractor = build(properties(true, true));
    }

    private void ocrReads(String text, double confidence) {
        int words = text.isBlank() ? 0 : text.split("\\s+").length;
        when(tesseract.readAll(anyList()))
                .thenReturn(List.of(new TesseractClient.FrameText(text, confidence, words)));
    }

    /** The whole point: clean local text never touches the vision model. */
    @Test
    void passesCleanOcrTextStraightThroughWithoutSpendingAVisionRequest() {
        ocrReads(GOOD_OCR, 88);

        Optional<VisualTextExtractor.VisualText> result = extractor.extract(URL, SAVE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo("ocr");
        assertThat(result.get().text()).contains("400g rigatoni");
        verify(gemini, never()).transcribeFrames(any(), anyList(), any());
        verify(budget, never()).acquireFor(anyString(), anyInt());
    }

    @Test
    void escalatesToVisionWhenOcrLandsBelowTheRepairFloor() {
        ocrReads("|][ ~~ 4/ >> ][| ~ // \\\\ %% ^^ ][", 30);
        when(budget.acquireFor(eq("flash"), eq(20))).thenReturn(null);
        when(gemini.transcribeFrames(eq(SAVE_ID), anyList(), any()))
                .thenReturn("400g rigatoni\n2 tbsp olive oil");

        Optional<VisualTextExtractor.VisualText> result = extractor.extract(URL, SAVE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo("ocr-vision");
        assertThat(result.get().text()).isEqualTo("400g rigatoni\n2 tbsp olive oil");
        verify(budget).acquireFor("flash", 20);
    }

    /**
     * Parking a save until tomorrow over an <em>optional</em> quality upgrade
     * would be the wrong trade — the user waits a day for a save that already
     * had text. The classify call's own {@code unusable} branch is the honest
     * place for junk to be caught.
     */
    @Test
    void degradesRatherThanParkingTheSaveWhenTheVisionPoolIsExhausted() {
        ocrReads("smudged pasta recipe with barely legible ingredient text here", 42);
        when(budget.acquireFor(anyString(), anyInt()))
                .thenThrow(new RetryAfterException("exhausted", Duration.ofHours(3)));

        Optional<VisualTextExtractor.VisualText> result = extractor.extract(URL, SAVE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo("ocr-degraded");
        assertThat(result.get().gateReason()).contains("exhausted");
        verify(gemini, never()).transcribeFrames(any(), anyList(), any());
    }

    @Test
    void degradesWhenVisionEscalationIsSwitchedOff() {
        extractor = build(properties(true, false));
        ocrReads("smudged pasta recipe with barely legible ingredient text here", 42);

        Optional<VisualTextExtractor.VisualText> result = extractor.extract(URL, SAVE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo("ocr-degraded");
        verify(budget, never()).acquireFor(anyString(), anyInt());
    }

    @Test
    void returnsEmptyWhenNeitherTierFoundAnything() {
        ocrReads("", 0);
        when(budget.acquireFor(anyString(), anyInt())).thenReturn(null);
        when(gemini.transcribeFrames(any(), anyList(), any())).thenReturn("");

        assertThat(extractor.extract(URL, SAVE_ID)).isEmpty();
    }

    @Test
    void doesNothingWhenTheTierIsDisabled() {
        extractor = build(properties(false, true));

        assertThat(extractor.extract(URL, SAVE_ID)).isEmpty();
        verify(ytDlp, never()).downloadVideo(anyString(), any(Path.class), anyInt());
    }

    @Test
    void returnsEmptyWhenTheSourceHasNoVideo() {
        when(ytDlp.downloadVideo(anyString(), any(Path.class), anyInt())).thenReturn(Optional.empty());

        assertThat(extractor.extract(URL, SAVE_ID)).isEmpty();
        verify(frames, never()).extract(any(Path.class), any(Path.class));
    }

    @Test
    void returnsEmptyWhenNoFramesCouldBeCut() {
        when(frames.extract(any(Path.class), any(Path.class)))
                .thenReturn(new FrameExtractor.Keyframes(List.of(), List.of()));

        assertThat(extractor.extract(URL, SAVE_ID)).isEmpty();
        verify(tesseract, never()).readAll(anyList());
    }

    /**
     * The tier is reached only after the probe already matched an extractor, so
     * a download failure here means this video is blocked or gone — not that
     * the site is unsupported. A permanently-failing one should end the tier
     * quietly and let the cascade report its own honest failure.
     */
    @Test
    void treatsAPermanentDownloadFailureAsNoVideoRatherThanFailingTheSave() {
        when(ytDlp.downloadVideo(anyString(), any(Path.class), anyInt()))
                .thenThrow(new YtDlpFailedException("exited 1",
                        "ERROR: Video unavailable. This video is private"));

        assertThat(extractor.extract(URL, SAVE_ID)).isEmpty();
    }

    @Test
    void propagatesARetryableDownloadFailure() {
        when(ytDlp.downloadVideo(anyString(), any(Path.class), anyInt()))
                .thenThrow(new YtDlpFailedException("exited 1",
                        "ERROR: unable to download video data: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> extractor.extract(URL, SAVE_ID))
                .isInstanceOf(RetryableJobException.class);
    }
}
