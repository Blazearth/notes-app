package com.weavr.extraction.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.weavr.extraction.artifact.ArtifactStore;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import com.weavr.extraction.process.ProcessExecutionException;
import com.weavr.extraction.process.TempDirs;
import com.weavr.extraction.ytdlp.YtDlpClient;
import com.weavr.extraction.ytdlp.YtDlpErrors;
import com.weavr.extraction.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The service-side half of the visual tier: download, cut frames, read them
 * with tesseract, vote, and judge — everything that touches the media bytes
 * (docs/extraction-architecture.md Part D, "Where the line sits"). Unlike
 * {@code api}'s {@code VisualTextExtractor}, this class never calls Gemini —
 * a failed gate returns the sharpest frames as artifacts instead, and the
 * backend decides whether to spend a Flash vision request on them.
 */
@Component
public class VisualExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisualExtractor.class);

    private final YtDlpClient ytDlp;
    private final FrameExtractor frames;
    private final TesseractClient tesseract;
    private final FrameTextVoter voter;
    private final OcrQualityGate gate;
    private final ThumbnailSelector thumbnails;
    private final ArtifactStore artifacts;
    private final OcrProperties properties;

    VisualExtractor(YtDlpClient ytDlp, FrameExtractor frames, TesseractClient tesseract,
                    FrameTextVoter voter, OcrQualityGate gate, ThumbnailSelector thumbnails,
                    ArtifactStore artifacts, OcrProperties properties) {
        this.ytDlp = ytDlp;
        this.frames = frames;
        this.tesseract = tesseract;
        this.voter = voter;
        this.gate = gate;
        this.thumbnails = thumbnails;
        this.artifacts = artifacts;
        this.properties = properties;
    }

    /**
     * @param text                  what was read off the frames — may be
     *                              below the repair floor when
     *                              {@code needsVisionEscalation} is true
     * @param source                {@code "ocr"} (passed the gate) or
     *                              {@code "ocr-below-floor"} (did not)
     * @param framesRead            how many frames yielded text
     * @param confidence            mean OCR confidence, 0–100
     * @param gateReason            why the gate decided as it did
     * @param needsVisionEscalation true when the gate failed and
     *                              {@code frameArtifacts} carries the
     *                              sharpest frames for the backend to send
     *                              to a vision model itself
     * @param frameArtifacts        stored colour frames, ranked by sharpness,
     *                              capped at {@code weavr.ocr.vision-frames};
     *                              empty unless escalating
     */
    public record VisualText(String text, String source, int framesRead, double confidence,
                             String gateReason, boolean needsVisionEscalation,
                             List<ArtifactStore.StoredArtifact> frameArtifacts) {
    }

    /**
     * @param prefix groups any stored frame artifacts under, e.g. the save
     *               id — passed straight through to {@link ArtifactStore#put}
     * @return the text read from the video's frames (with or without an
     *         escalation flag), or empty when the tier is disabled, the
     *         source has no video, or no frames could be cut at all
     */
    public Optional<VisualText> extract(String url, int maxDurationSeconds, String prefix) {
        if (!properties.enabled()) {
            log.debug("Visual tier is disabled; not attempting OCR for {}", url);
            return Optional.empty();
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-extraction-ocr-");

            Optional<Path> video = downloadVideo(url, workDir, maxDurationSeconds);
            if (video.isEmpty()) {
                return Optional.empty();
            }

            FrameExtractor.Keyframes keyframes = frames.extract(video.get(), workDir);
            if (keyframes.isEmpty()) {
                log.debug("No frames could be cut from {}", url);
                return Optional.empty();
            }

            FrameTextVoter.VotedText voted = voter.vote(tesseract.readAll(keyframes.ocr()));
            OcrQualityGate.Decision decision = gate.judge(voted);

            log.info("OCR on {}: {} frames, {} lines kept, {} dropped, mean confidence {} -> {} ({})",
                    url, keyframes.size(), voted.linesKept(), voted.linesDropped(),
                    Math.round(voted.meanConfidence()), decision.verdict(), decision.reason());

            if (decision.passed()) {
                return Optional.of(new VisualText(voted.text(), "ocr", voted.framesRead(),
                        voted.meanConfidence(), decision.reason(), false, List.of()));
            }

            return Optional.of(belowFloor(keyframes, voted, decision, prefix));

        } catch (IOException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR,
                    "Could not create a temp directory for frames.", e);
        } catch (ProcessExecutionException e) {
            // A missing tesseract or ffmpeg is a deployment fault, not the
            // URL's — retryable so the work survives a container rebuilt
            // with the binary present.
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR,
                    "The OCR toolchain is not runnable: " + e.getMessage(), e);
        } finally {
            TempDirs.deleteQuietly(workDir);
        }
    }

    private Optional<Path> downloadVideo(String url, Path workDir, int maxDurationSeconds) {
        try {
            return ytDlp.downloadVideo(url, workDir, maxDurationSeconds);
        } catch (YtDlpFailedException e) {
            // Reached only after the probe already matched an extractor, so a
            // failure here means this particular video is blocked or gone —
            // not that the site is unsupported.
            log.debug("Video download failed for {}: {}", url, e.getMessage());
            ExtractionException translated = YtDlpErrors.toException(e);
            if (translated.code().retryable()) {
                throw translated;
            }
            return Optional.empty();
        }
    }

    /**
     * Below the repair floor, but not necessarily empty. If vision escalation
     * is enabled, the sharpest frames are stored as artifacts and the caller
     * is told so; otherwise the below-floor text itself is returned as the
     * result, same as the monolith's own "degrade" path.
     */
    private VisualText belowFloor(FrameExtractor.Keyframes keyframes, FrameTextVoter.VotedText voted,
                                  OcrQualityGate.Decision decision, String prefix) {
        if (!properties.visionEscalation()) {
            return new VisualText(voted.text(), "ocr-below-floor", voted.framesRead(),
                    voted.meanConfidence(), decision.reason(), false, List.of());
        }

        List<Path> chosen = thumbnails.best(keyframes.colour(), properties.visionFrames());
        List<ArtifactStore.StoredArtifact> stored = chosen.stream()
                .map(path -> storeFrame(path, prefix))
                .flatMap(Optional::stream)
                .toList();

        if (stored.isEmpty()) {
            // Frames could not be read back for storage — fall through to
            // whatever text was voted, same as no escalation being available.
            return new VisualText(voted.text(), "ocr-below-floor", voted.framesRead(),
                    voted.meanConfidence(), decision.reason(), false, List.of());
        }

        return new VisualText(voted.text(), "ocr-below-floor", voted.framesRead(),
                voted.meanConfidence(), decision.reason(), true, stored);
    }

    private Optional<ArtifactStore.StoredArtifact> storeFrame(Path path, String prefix) {
        try {
            return Optional.of(artifacts.put(prefix, "frame", Files.readAllBytes(path)));
        } catch (IOException e) {
            log.debug("Could not read frame {} to store as an artifact: {}", path.getFileName(), e.toString());
            return Optional.empty();
        }
    }
}
