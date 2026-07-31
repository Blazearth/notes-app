package com.weavr.api.pipeline.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.weavr.api.pipeline.ExternalProcess;
import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.audio.FfmpegProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The frame-cutting pass.
 *
 * <p>These are mocked-process tests, and the honest limit of that is on record
 * three times over in this repo: mocking hid three yt-dlp defects and a UTF-8
 * bug. The filter graph in particular cannot be judged here — whether it
 * actually selects frames is a fact about ffmpeg, and {@code OcrLiveTest} is
 * what asks ffmpeg. What these pin is the surrounding logic: argument shape,
 * pairing, and the read-before-you-judge ordering.
 */
class FrameExtractorTest {

    private final ExternalProcess processes = mock(ExternalProcess.class);
    private final FrameExtractor extractor = new FrameExtractor(
            processes,
            new FfmpegProperties("ffmpeg", Duration.ofSeconds(60)),
            properties());

    private static OcrProperties properties() {
        return new OcrProperties(true, "tesseract", "eng", 6, Duration.ofSeconds(30),
                90, 12, 0.25, 150, null, 60.0, 8, 0.5, 2, true, 4);
    }

    private static ExternalProcess.Result ok() {
        return new ExternalProcess.Result(0, "", "", false, false);
    }

    private static ExternalProcess.Result failed() {
        return new ExternalProcess.Result(1, "", "ffmpeg: invalid data found", false, false);
    }

    /** Stands in for what the real ffmpeg would leave behind. */
    private static void writeFrames(Path dir, int colour, int ocr) throws IOException {
        for (int i = 1; i <= colour; i++) {
            Files.writeString(dir.resolve("colour-%03d.jpg".formatted(i)), "jpg");
        }
        for (int i = 1; i <= ocr; i++) {
            Files.writeString(dir.resolve("ocr-%03d.png".formatted(i)), "png");
        }
    }

    private List<String> commandFor(Path video, Path dir) {
        var captor = forClass(List.class);
        verify(processes).run(captor.capture(), any(Duration.class));
        return captor.getValue();
    }

    /**
     * The defect this pins was found by running the real binary, not by
     * reasoning: the obvious chain — {@code select='gt(scene,0.25)',mpdecimate}
     * — produces <b>zero</b> frames on a video that is one static ingredient
     * card start to finish, because frame 0 has nothing to differ from and
     * nothing after it changes. That is exactly the content this tier exists
     * for, so both extra selector terms are load-bearing.
     */
    @Test
    void selectsFrameZeroAndAPeriodicSampleAsWellAsSceneChanges(@TempDir Path dir) throws IOException {
        writeFrames(dir, 1, 1);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        extractor.extract(dir.resolve("v.mp4"), dir);

        String filter = commandFor(dir.resolve("v.mp4"), dir).stream()
                .filter(arg -> arg.contains("select="))
                .findFirst().orElseThrow();
        assertThat(filter)
                .contains("gt(scene,0.25)")
                .contains("eq(n,0)")
                .contains("not(mod(n,150))")
                .contains("mpdecimate");
    }

    /**
     * One decode, two outputs. Two ffmpeg runs would each re-run scene
     * detection over a stateful {@code mpdecimate}, and nothing would guarantee
     * they agreed on which frames survived — which is what index-pairing needs.
     */
    @Test
    void cutsBothBranchesFromOneDecodeAndDoesNoContrastWork(@TempDir Path dir) throws IOException {
        writeFrames(dir, 1, 1);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        extractor.extract(dir.resolve("v.mp4"), dir);

        List<String> command = commandFor(dir.resolve("v.mp4"), dir);
        assertThat(command).containsSubsequence("-filter_complex")
                .containsSubsequence("-map", "[colour]")
                .containsSubsequence("-map", "[ocr]");

        String filter = command.stream().filter(a -> a.contains("split=2")).findFirst().orElseThrow();
        assertThat(filter).contains("format=gray")
                // histeq measurably damages high-contrast overlay text: it
                // flattens a white-on-near-black card to mid-grey and hollows
                // the glyphs. Verified on a rendered frame, not assumed.
                .doesNotContain("histeq");
    }

    @Test
    void pairsColourAndOcrFramesByIndex(@TempDir Path dir) throws IOException {
        writeFrames(dir, 3, 3);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        FrameExtractor.Keyframes frames = extractor.extract(dir.resolve("v.mp4"), dir);

        assertThat(frames.size()).isEqualTo(3);
        assertThat(frames.colour().get(1).getFileName()).hasToString("colour-002.jpg");
        assertThat(frames.ocr().get(1).getFileName()).hasToString("ocr-002.png");
    }

    /**
     * A truncated run can leave the two per-output caps uneven. An OCR frame
     * with no colour twin cannot be escalated to vision, so pairing has to stop
     * at the shorter list rather than index off the end of it.
     */
    @Test
    void pairsOnlyAsFarAsBothBranchesGo(@TempDir Path dir) throws IOException {
        writeFrames(dir, 2, 4);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        FrameExtractor.Keyframes frames = extractor.extract(dir.resolve("v.mp4"), dir);

        assertThat(frames.colour()).hasSize(2);
        assertThat(frames.ocr()).hasSize(2);
    }

    /**
     * Same lesson the caption path learned the hard way: ffmpeg exiting
     * non-zero over a truncated final frame must not throw away frames already
     * written and perfectly usable.
     */
    @Test
    void keepsFramesWrittenBeforeANonZeroExit(@TempDir Path dir) throws IOException {
        writeFrames(dir, 2, 2);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(failed());

        assertThat(extractor.extract(dir.resolve("v.mp4"), dir).size()).isEqualTo(2);
    }

    @Test
    void throwsWhenFfmpegFailedAndWroteNothing(@TempDir Path dir) {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(failed());

        assertThatThrownBy(() -> extractor.extract(dir.resolve("v.mp4"), dir))
                .isInstanceOf(ProcessExecutionException.class)
                .hasMessageContaining("v.mp4");
    }

    /** A clean run that selected nothing is an empty result, not a failure. */
    @Test
    void returnsEmptyWhenFfmpegSucceededButSelectedNoFrames(@TempDir Path dir) {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        assertThat(extractor.extract(dir.resolve("v.mp4"), dir).isEmpty()).isTrue();
    }

    @Test
    void capsFrameCountOnBothOutputs(@TempDir Path dir) throws IOException {
        writeFrames(dir, 1, 1);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        extractor.extract(dir.resolve("v.mp4"), dir);

        assertThat(commandFor(dir.resolve("v.mp4"), dir))
                .filteredOn("-frames:v"::equals).hasSize(2);
    }
}
