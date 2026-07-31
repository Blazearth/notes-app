package com.weavr.api.pipeline.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.gemini.GeminiClient;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.TempDirs;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpErrors;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Step 5 of the extraction cascade — the visual tier, and the differentiator.
 *
 * <p>The case it exists for: a recipe Reel whose ingredients are burned into
 * the pixels as overlay text, with no caption, no description and no voiceover.
 * Steps 1–4 all return nothing on it, and it is common.
 *
 * <p><b>The vision model is not the OCR engine.</b> Reading the text locally
 * and feeding the <em>text</em> into the cheap classify call is the entire
 * design: vision calls are the most expensive path in the system and they draw
 * on the scarcer Flash pool, so the way to afford them is to rarely need them.
 * The sequence is:
 *
 * <ol>
 *   <li>Download the worst video stream that still has legible text, bounded.</li>
 *   <li>Cut deduped keyframes in one ffmpeg pass.</li>
 *   <li>Read each frame with tesseract.</li>
 *   <li>Vote across frames — the same overlay read several times corrects its
 *       own errors for free.</li>
 *   <li>Judge the result. Above the repair floor, it goes to the cheap model
 *       like any other text save; below it, <em>then</em> spend one Flash
 *       vision request on the sharpest frames.</li>
 * </ol>
 *
 * <p>Nothing survives this method. The video, the frames and the temp directory
 * are gone before it returns — storage cost, legal exposure, and a free-tier
 * disk that a couple of concurrent jobs would otherwise fill.
 */
@Component
public class VisualTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisualTextExtractor.class);

    private final YtDlpClient ytDlp;
    private final FrameExtractor frames;
    private final TesseractClient tesseract;
    private final FrameTextVoter voter;
    private final OcrQualityGate gate;
    private final ThumbnailSelector thumbnails;
    private final GeminiClient gemini;
    private final GeminiBudgetService budget;
    private final GeminiProperties geminiProperties;
    private final OcrProperties properties;

    VisualTextExtractor(YtDlpClient ytDlp, FrameExtractor frames, TesseractClient tesseract,
                        FrameTextVoter voter, OcrQualityGate gate, ThumbnailSelector thumbnails,
                        GeminiClient gemini, GeminiBudgetService budget,
                        GeminiProperties geminiProperties, OcrProperties properties) {
        this.ytDlp = ytDlp;
        this.frames = frames;
        this.tesseract = tesseract;
        this.voter = voter;
        this.gate = gate;
        this.thumbnails = thumbnails;
        this.gemini = gemini;
        this.budget = budget;
        this.geminiProperties = geminiProperties;
        this.properties = properties;
    }

    /**
     * @param text          what was read off the frames
     * @param source        {@code "ocr"} or {@code "ocr-vision"} — which tier
     *                      produced it, so the escalation rate is measurable
     * @param framesRead    how many frames yielded text
     * @param confidence    mean OCR confidence (0 for a vision result, which has
     *                      no comparable number)
     * @param gateReason    why the gate decided as it did
     */
    public record VisualText(String text, String source, int framesRead,
                             double confidence, String gateReason) {
    }

    /**
     * @param saveId used only for {@code gemini_calls} attribution on an escalation
     * @return the text read from the video's frames, or empty when the tier is
     *         disabled, the source has no video, or nothing legible was found
     */
    public Optional<VisualText> extract(String url, UUID saveId) {
        if (!properties.enabled()) {
            log.debug("Visual tier is disabled; not attempting OCR for {}", url);
            return Optional.empty();
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-ocr-");

            Optional<Path> video = downloadVideo(url, workDir);
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

            log.info("OCR on {}: {} frames, {} lines kept, {} dropped, mean confidence {} → {} ({})",
                    url, keyframes.size(), voted.linesKept(), voted.linesDropped(),
                    Math.round(voted.meanConfidence()), decision.verdict(), decision.reason());

            if (decision.passed()) {
                return Optional.of(new VisualText(voted.text(), "ocr", voted.framesRead(),
                        voted.meanConfidence(), decision.reason()));
            }

            return escalate(saveId, keyframes, voted, decision);

        } catch (IOException e) {
            throw new RetryableJobException("Could not create a temp directory for frames", e);
        } catch (ProcessExecutionException e) {
            // A missing tesseract or ffmpeg is a deployment fault, not the
            // save's — retryable so the work survives a container rebuilt with
            // the binary present.
            throw new RetryableJobException(
                    "The OCR toolchain is not runnable: " + e.getMessage(), e);
        } finally {
            TempDirs.deleteQuietly(workDir);
        }
    }

    private Optional<Path> downloadVideo(String url, Path workDir) {
        try {
            return ytDlp.downloadVideo(url, workDir, properties.maxVideoSeconds());
        } catch (YtDlpFailedException e) {
            // Reached only after the probe already matched an extractor, so a
            // failure here means this particular video is blocked or gone —
            // not that the site is unsupported. Either way the cascade's own
            // no_text_extracted failure is the honest outcome, unless the error
            // is one worth retrying.
            log.debug("Video download failed for {}: {}", url, e.getMessage());
            RuntimeException translated = YtDlpErrors.toException(e);
            if (translated instanceof RetryableJobException) {
                throw translated;
            }
            return Optional.empty();
        }
    }

    /**
     * Tier 2. Only reached when local OCR came back below the repair floor —
     * where the model would not repair the text but invent plausible
     * replacements for it, which is worse than failing because nothing
     * downstream can tell the difference.
     */
    private Optional<VisualText> escalate(UUID saveId, FrameExtractor.Keyframes keyframes,
                                          FrameTextVoter.VotedText voted,
                                          OcrQualityGate.Decision decision) {
        if (!properties.visionEscalation()) {
            return degrade(voted, decision, "vision escalation is disabled");
        }

        // Sharpest frames only: a motion-blurred frame costs tokens and returns
        // nothing, and the ranking is free (no model call — CA#15).
        List<Path> chosen = thumbnails.best(keyframes.colour(), properties.visionFrames());
        List<byte[]> images = readFrames(chosen);
        if (images.isEmpty()) {
            return degrade(voted, decision, "no frames could be read back for vision");
        }

        BudgetApproved approved;
        try {
            approved = budget.acquireFor(
                    geminiProperties.fallbackModel(), geminiProperties.fallbackRpd());
        } catch (RuntimeException e) {
            // The vision pool is exhausted. Parking the save until tomorrow over
            // an *optional* quality upgrade would be the wrong trade — degrade
            // instead, and let the classify call see the OCR text such as it is.
            return degrade(voted, decision,
                    "vision pool exhausted: " + e.getMessage());
        }

        String text = gemini.transcribeFrames(saveId, images, approved);
        if (text.isBlank()) {
            return degrade(voted, decision, "vision found no text either");
        }

        return Optional.of(new VisualText(text, "ocr-vision", keyframes.size(), 0.0,
                decision.reason()));
    }

    /**
     * Falls back to the local OCR text when escalation is unavailable. Below the
     * floor it may well be junk — but {@code unusable} is a first-class outcome
     * of the classify call, so handing the model the honest text lets it say so,
     * where returning nothing here would fail the save with a less accurate
     * message.
     */
    private Optional<VisualText> degrade(FrameTextVoter.VotedText voted,
                                         OcrQualityGate.Decision decision, String why) {
        if (voted.isEmpty() || voted.text().isBlank()) {
            log.debug("No OCR text and no escalation ({})", why);
            return Optional.empty();
        }
        log.info("Using below-floor OCR text ({}): {}", why, decision.reason());
        return Optional.of(new VisualText(voted.text(), "ocr-degraded", voted.framesRead(),
                voted.meanConfidence(), decision.reason() + "; " + why));
    }

    private static List<byte[]> readFrames(List<Path> paths) {
        List<byte[]> images = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                images.add(Files.readAllBytes(path));
            } catch (IOException e) {
                log.debug("Could not read frame {} for vision: {}", path.getFileName(), e.toString());
            }
        }
        return images;
    }

    /** Stage breadcrumb, kept small — it is a diagnostic, not a payload. */
    public static Map<String, Object> stagePayload(VisualText visual) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", visual.source());
        payload.put("framesRead", visual.framesRead());
        payload.put("meanConfidence", Math.round(visual.confidence()));
        payload.put("gate", visual.gateReason());
        payload.put("textLength", visual.text().length());
        return payload;
    }
}
