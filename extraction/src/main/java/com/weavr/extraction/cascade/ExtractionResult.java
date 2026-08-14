package com.weavr.extraction.cascade;

import java.util.List;

import com.weavr.extraction.artifact.ArtifactStore;

/**
 * The wire shape of a successful {@code result} (docs/extraction-architecture.md
 * Part D). Text comes back inline because it is what the pipeline actually
 * wants and it is small; media never does — an artifact is always an opaque,
 * signed, expiring reference.
 *
 * @param source                 which step produced the text — {@code captions},
 *                               {@code metadata}, {@code link}, {@code pdf},
 *                               {@code ocr}, {@code ocr-below-floor}, or
 *                               {@code audio} when only an artifact came back
 * @param text                   the assembled text blob, possibly empty when
 *                               {@code needsTranscription} or
 *                               {@code needsVisionEscalation} is the real answer
 * @param needsTranscription     true when an audio artifact was returned
 *                               instead of text — the backend owns the Groq call
 * @param needsVisionEscalation  true when OCR came back below the repair floor
 *                               and {@code artifacts} carries the sharpest
 *                               frames for the backend to decide on
 * @param metadata               the probe result, for enrichment and the thumbnail
 * @param artifacts              stored media this result references, if any
 */
public record ExtractionResult(
        String source,
        String text,
        boolean needsTranscription,
        boolean needsVisionEscalation,
        ExtractionMetadata metadata,
        List<ArtifactStore.StoredArtifact> artifacts
) {
}
