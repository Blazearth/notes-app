package com.weavr.extraction.ocr;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the visual tier. Ported unchanged from {@code api}'s
 * {@code OcrProperties} — measured settings intact, per
 * docs/extraction-architecture.md Phase 3 ("port the OCR tier with its
 * measured settings intact... both were established by measurement and are
 * easy to lose in a rewrite").
 *
 * @param enabled               kill switch
 * @param binary                tesseract executable
 * @param language              tesseract {@code -l} value
 * @param pageSegmentation      tesseract {@code --psm}
 * @param timeout               bound on one tesseract run, over one frame
 * @param maxVideoSeconds       how much of the source is downloaded for frames
 * @param maxFrames             upper bound on frames handed to OCR
 * @param sceneThreshold        ffmpeg {@code select='gt(scene,N)'} threshold
 * @param periodicSampleFrames  force a sample every N source frames regardless
 *                              of scene score — a static card has no scene
 *                              changes at all
 * @param frameFilters          the ffmpeg filter chain applied to the OCR
 *                              branch. Deliberately does <em>no</em> contrast
 *                              work by default — see {@link FrameExtractor}
 * @param minMeanConfidence     tesseract's own mean per-word confidence (0–100)
 *                              below which the text is assumed to be under the
 *                              LLM repair floor
 * @param minWords              fewer usable words than this is nothing to work with
 * @param minAlphaRatio         fraction of tokens that must contain a letter —
 *                              tesseract's hard-failure shape is confident symbol soup
 * @param minFrameVotes         how many frames a line must appear in to be
 *                              trusted, when there are enough frames to vote
 * @param visionEscalation      whether a failed gate returns frame artifacts
 *                              for the backend to escalate to vision. With
 *                              this off, below-floor OCR text is returned as
 *                              the result with no artifacts, and the backend
 *                              decides what to do with a low-confidence answer
 * @param visionFrames          how many frames a gate failure carries as
 *                              artifacts, ranked by sharpness
 */
@ConfigurationProperties(prefix = "weavr.ocr")
public record OcrProperties(
        boolean enabled,
        String binary,
        String language,
        int pageSegmentation,
        Duration timeout,
        int maxVideoSeconds,
        int maxFrames,
        double sceneThreshold,
        int periodicSampleFrames,
        String frameFilters,
        double minMeanConfidence,
        int minWords,
        double minAlphaRatio,
        int minFrameVotes,
        boolean visionEscalation,
        int visionFrames
) {

    public OcrProperties {
        if (binary == null || binary.isBlank()) binary = "tesseract";
        if (language == null || language.isBlank()) language = "eng";
        if (pageSegmentation <= 0) pageSegmentation = 6;
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (maxVideoSeconds <= 0) maxVideoSeconds = 90;
        if (maxFrames <= 0) maxFrames = 12;
        if (sceneThreshold <= 0) sceneThreshold = 0.25;
        if (periodicSampleFrames <= 0) periodicSampleFrames = 150;
        if (frameFilters == null || frameFilters.isBlank()) {
            frameFilters = "scale=iw*2:-2:flags=lanczos,format=gray";
        }
        if (minMeanConfidence <= 0) minMeanConfidence = 60.0;
        if (minWords <= 0) minWords = 8;
        if (minAlphaRatio <= 0) minAlphaRatio = 0.5;
        if (minFrameVotes <= 0) minFrameVotes = 2;
        if (visionFrames <= 0) visionFrames = 4;
    }
}
