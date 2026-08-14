package com.weavr.extraction.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.weavr.extraction.process.ExternalProcess;
import com.weavr.extraction.process.ProcessExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cuts a downloaded video into the small set of frames the visual tier reads,
 * in <b>one</b> ffmpeg pass. Ported unchanged from {@code api}'s
 * {@code FrameExtractor} — docs/extraction-architecture.md Phase 3 calls this
 * out by name: "the frame selector that also takes frame 0 and a periodic
 * sample, and no histogram equalisation. Both were established by measurement
 * and are easy to lose in a rewrite."
 *
 * <h2>Two things about the filter chain that are not obvious</h2>
 *
 * <p><b>Scene detection alone selects nothing on a static card.</b> The obvious
 * chain — {@code select='gt(scene,0.25)',mpdecimate} — yields <em>zero</em>
 * frames for a video that is one unchanging ingredient card start to finish,
 * because the first frame has no predecessor to differ from and nothing after
 * it changes. The selector also takes frame 0 unconditionally and a periodic
 * sample thereafter; {@code mpdecimate} then collapses the duplicates that
 * produces. Measured: 0 frames before, 1 after, on a fully static 12-second card.
 *
 * <p><b>Histogram equalisation makes overlay text worse, not better.</b>
 * {@code histeq} is a global remap, and overlay text is high-contrast and
 * bimodal by design — running it on a white-on-near-black card flattens the
 * background to mid-grey and erodes the glyphs into speckled outlines.
 * Measured on one rendered card, real tesseract, same frame both ways: the
 * default chain read 16 of 16 words at mean confidence 95; {@code histeq} read
 * 7 garbled tokens at mean confidence 22 and lost a line outright.
 */
@Component
public class FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractor.class);

    private static final String COLOUR_PREFIX = "colour-";
    private static final String OCR_PREFIX = "ocr-";

    private final ExternalProcess processes;
    private final FfmpegProperties ffmpeg;
    private final OcrProperties properties;

    FrameExtractor(ExternalProcess processes, FfmpegProperties ffmpeg, OcrProperties properties) {
        this.processes = processes;
        this.ffmpeg = ffmpeg;
        this.properties = properties;
    }

    /**
     * Paired frame lists. {@code colour.get(i)} and {@code ocr.get(i)} are the
     * same source frame.
     */
    public record Keyframes(List<Path> colour, List<Path> ocr) {

        public boolean isEmpty() {
            return ocr.isEmpty();
        }

        public int size() {
            return ocr.size();
        }
    }

    /**
     * @param video   the downloaded source, deleted by the caller
     * @param workDir where frames are written; the caller owns cleanup
     */
    public Keyframes extract(Path video, Path workDir) {
        int max = properties.maxFrames();

        ExternalProcess.Result result = processes.run(List.of(
                ffmpeg.binary(),
                "-y",
                "-nostdin",
                "-i", video.toString(),
                "-filter_complex", filterGraph(),
                "-map", "[colour]",
                "-fps_mode", "vfr",
                "-q:v", "4",
                "-frames:v", String.valueOf(max),
                workDir.resolve(COLOUR_PREFIX + "%03d.jpg").toString(),
                "-map", "[ocr]",
                "-fps_mode", "vfr",
                "-frames:v", String.valueOf(max),
                workDir.resolve(OCR_PREFIX + "%03d.png").toString()),
                ffmpeg.timeout());

        List<Path> colour = framesNamed(workDir, COLOUR_PREFIX);
        List<Path> ocr = framesNamed(workDir, OCR_PREFIX);

        if (ocr.isEmpty()) {
            if (!result.succeeded()) {
                throw new ProcessExecutionException(
                        "ffmpeg could not cut frames from " + video.getFileName() + ": "
                                + describe(result), null);
            }
            log.debug("ffmpeg selected no frames from {}", video.getFileName());
            return new Keyframes(List.of(), List.of());
        }

        if (!result.succeeded()) {
            log.debug("ffmpeg exited {} but wrote {} usable frames; keeping them",
                    result.exitCode(), ocr.size());
        }

        int paired = Math.min(colour.size(), ocr.size());
        if (colour.size() != ocr.size()) {
            log.debug("Frame branches came out uneven ({} colour, {} ocr); pairing {}",
                    colour.size(), ocr.size(), paired);
        }
        return new Keyframes(List.copyOf(colour.subList(0, paired)),
                List.copyOf(ocr.subList(0, paired)));
    }

    private String filterGraph() {
        String selector = "gt(scene,%s)+eq(n,0)+not(mod(n,%d))"
                .formatted(properties.sceneThreshold(), properties.periodicSampleFrames());
        return "[0:v]select='" + selector + "',mpdecimate,split=2[c][g];"
                + "[c]scale=768:-2[colour];"
                + "[g]" + properties.frameFilters() + "[ocr]";
    }

    private static List<Path> framesNamed(Path workDir, String prefix) {
        try (Stream<Path> files = Files.list(workDir)) {
            List<Path> found = new ArrayList<>(files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .toList());
            found.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return found;
        } catch (IOException e) {
            log.warn("Could not list extracted frames in {}", workDir, e);
            return List.of();
        }
    }

    private static String describe(ExternalProcess.Result result) {
        if (result.timedOut()) {
            return "timed out";
        }
        return "exited " + result.exitCode() + ": " + result.stderr();
    }
}
