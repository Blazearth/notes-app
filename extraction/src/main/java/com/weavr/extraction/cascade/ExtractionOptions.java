package com.weavr.extraction.cascade;

/**
 * What the caller is asking for beyond captions/metadata/link/PDF, which are
 * always attempted. Both extra tiers touch media bytes and cost real time, so
 * neither runs unless asked for — mirroring the doc's own request shape
 * ({@code options.audio}, {@code options.frames}).
 *
 * @param audio               attempt an audio download (for a needsTranscription artifact) when captions/metadata are thin
 * @param frames              attempt the visual (OCR) tier when captions/metadata/audio are thin or skipped
 * @param maxDurationSeconds  caller-supplied override for how much of the source is downloaded; null uses this service's own defaults
 */
public record ExtractionOptions(boolean audio, boolean frames, Integer maxDurationSeconds) {

    public static ExtractionOptions defaults() {
        return new ExtractionOptions(false, false, null);
    }
}
