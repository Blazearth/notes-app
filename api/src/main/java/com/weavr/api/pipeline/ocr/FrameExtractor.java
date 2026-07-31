package com.weavr.api.pipeline.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.weavr.api.pipeline.ExternalProcess;
import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.audio.FfmpegProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cuts a downloaded video into the small set of frames the visual tier reads,
 * in <b>one</b> ffmpeg pass.
 *
 * <p>Two outputs come out of that single decode, split after the frame
 * selection so both branches see exactly the same frames in the same order:
 *
 * <ul>
 *   <li><b>Colour</b>, scaled to 768px wide — what a Flash vision escalation
 *       would carry, and what {@link ThumbnailSelector} ranks.</li>
 *   <li><b>Grey</b>, upscaled 2× — what tesseract reads.</li>
 * </ul>
 *
 * <p>Splitting inside the filter graph rather than running ffmpeg twice is not
 * an optimisation for its own sake: two passes would each re-run scene
 * detection, and {@code mpdecimate} is stateful, so nothing guarantees the two
 * runs agree on which frames survived. Index {@code n} of one list has to be
 * index {@code n} of the other for OCR text to be attributable back to an
 * image, and a single graph is what actually guarantees that.
 *
 * <h2>Two things about the filter chain that are not obvious</h2>
 *
 * <p><b>Scene detection alone selects nothing on a static card.</b> The obvious
 * chain — {@code select='gt(scene,0.25)',mpdecimate} — yields <em>zero</em>
 * frames for a video that is one unchanging ingredient card start to finish,
 * because the first frame has no predecessor to differ from and nothing after
 * it changes. That is precisely the content this whole tier exists for, so the
 * selector also takes frame 0 unconditionally and a periodic sample thereafter;
 * {@code mpdecimate} then collapses the duplicates that produces back down.
 * Measured: 0 frames before, 1 after, on a fully static 12-second card.
 *
 * <p><b>Histogram equalisation makes overlay text worse, not better.</b>
 * {@code histeq} is a global remap, and overlay text is high-contrast and
 * bimodal by design — running it on a white-on-near-black card flattens the
 * background to mid-grey and erodes the glyphs into speckled outlines. The
 * default chain therefore does no contrast work at all, only an upscale and a
 * greyscale conversion. It is a property rather than a constant because a real
 * Reel with text over a photographic background is a different problem from a
 * synthetic card, and the eval set — not this comment — is what should decide
 * whether that case needs a contrast stage.
 *
 * <p>Measured on one rendered card, real tesseract, same frame both ways: the
 * default chain read 16 of 16 words at mean confidence 95; {@code histeq} read
 * 7 garbled tokens at mean confidence 22 ({@code nigatoni},
 * {@code (icupiheavy}) and lost a line outright. 22 is below the escalation
 * floor, so the "obvious" preprocessing would not merely have degraded the
 * text — it would have spent a Flash vision request on content the plain chain
 * reads perfectly.
 *
 * <h2>A bound worth knowing about</h2>
 *
 * <p>{@code -frames:v} stops the run as soon as the cap is reached, so on a
 * source with many scene changes these are the <em>first</em> N selected
 * frames, not a spread across the clip. That is deliberate — it means a 90
 * second video costs a fraction of a second rather than a full decode — but it
 * does bias sampling toward the opening, and text that only appears at the end
 * of a busy clip can be missed. Acceptable for the content this tier targets
 * (Reels and Shorts, where the card is usually on screen throughout); revisit
 * if long-form ever matters.
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
     * same source frame, which is what lets OCR output be traced back to an
     * image for a vision escalation.
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

        // Read the directory before judging the exit code, for the same reason
        // the caption path does: a non-zero exit over a truncated final frame
        // would otherwise discard frames already written and perfectly usable.
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

        // The two -frames:v caps are applied per output, so a truncated run can
        // leave the branches uneven. Pair only as far as both go: an OCR frame
        // with no colour twin cannot be escalated to vision, and a colour frame
        // with no OCR twin was never read.
        int paired = Math.min(colour.size(), ocr.size());
        if (colour.size() != ocr.size()) {
            log.debug("Frame branches came out uneven ({} colour, {} ocr); pairing {}",
                    colour.size(), ocr.size(), paired);
        }
        return new Keyframes(List.copyOf(colour.subList(0, paired)),
                List.copyOf(ocr.subList(0, paired)));
    }

    /**
     * {@code select} takes frame 0 and a periodic sample as well as scene
     * changes — see the class comment; without those terms a static card
     * produces nothing. {@code mpdecimate} then drops the near-duplicates that
     * introduces, so a 5-second ingredient card does not become 5 identical
     * frames and 5 identical tesseract runs.
     */
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
            // ffmpeg's %03d counter is zero-padded, so lexical order is frame
            // order — but only up to 999 frames, and maxFrames is far below it.
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
