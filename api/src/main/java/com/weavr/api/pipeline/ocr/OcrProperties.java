package com.weavr.api.pipeline.ocr;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the visual tier — the hard case: a recipe Reel whose ingredients
 * exist only as on-screen overlay text, with no caption, no description and no
 * voiceover, which defeats cascade steps 1–3 entirely.
 *
 * @param enabled               kill switch. With this off the cascade fails such
 *                              a save honestly rather than half-running a tier
 *                              whose binary is missing
 * @param binary                tesseract executable. The Docker image must
 *                              install it alongside yt-dlp and ffmpeg
 * @param language              tesseract {@code -l} value. {@code tessdata_fast}
 *                              English is ~2 MB and is the intended default —
 *                              OCR only has to be good enough for the model to
 *                              repair
 * @param pageSegmentation      tesseract {@code --psm}. 6 = "a single uniform
 *                              block of text", which is what a burned-in overlay
 *                              card actually is. 11 (sparse text) is the
 *                              alternative worth measuring on the eval set
 * @param timeout               bound on one tesseract run, over one frame
 * @param maxVideoSeconds       how much of the source is downloaded for frames.
 *                              Reels and Shorts are shorter than this anyway;
 *                              the cap is what stops one long-form video from
 *                              stalling a worker
 * @param maxFrames             upper bound on frames handed to OCR. Each is a
 *                              separate tesseract process, so this is the tier's
 *                              real cost knob
 * @param sceneThreshold        ffmpeg {@code select='gt(scene,N)'} threshold
 * @param periodicSampleFrames  force a sample every N source frames regardless
 *                              of scene score — a video that is one static
 *                              ingredient card start to finish has no scene
 *                              changes at all, and that is precisely the case
 *                              this tier exists for
 * @param frameFilters          the ffmpeg filter chain applied to the OCR
 *                              branch. Deliberately does <em>no</em> contrast
 *                              work by default: overlay text is high-contrast
 *                              and bimodal by design, and a global equalisation
 *                              ({@code histeq}) measurably damages it — a
 *                              white-on-near-black card comes back mid-grey with
 *                              hollowed, speckled glyphs. A property rather than
 *                              a constant so the eval set can add a contrast
 *                              stage for text over photographic backgrounds
 *                              without a code change
 * @param minMeanConfidence     tesseract's own mean per-word confidence (0–100)
 *                              below which the text is assumed to be under the
 *                              LLM repair floor
 * @param minWords              fewer usable words than this is nothing to work
 *                              with, whatever the confidence says
 * @param minAlphaRatio         fraction of tokens that must contain a letter.
 *                              Tesseract fails hard rather than gracefully on
 *                              low-contrast text over gradients, and its failure
 *                              shape is confident symbol soup
 * @param minFrameVotes         how many frames a line must appear in to be
 *                              trusted, when there are enough frames to vote.
 *                              Burned-in overlays persist for many frames, so
 *                              consensus across duplicates corrects individual
 *                              misreads for free
 * @param visionEscalation      whether a failed gate may spend one Flash vision
 *                              request. With this off, low-confidence OCR text
 *                              is passed through as-is rather than escalated
 * @param visionFrames          how many frames the escalation carries. Ranked by
 *                              sharpness — a blurry frame costs tokens and
 *                              returns nothing
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
