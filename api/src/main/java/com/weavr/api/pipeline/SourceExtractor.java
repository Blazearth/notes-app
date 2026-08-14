package com.weavr.api.pipeline;

import java.util.UUID;

/**
 * The one thing a save's processing needs from wherever its text comes from —
 * captions, metadata, ASR, OCR, a plain link, a PDF. {@link ExtractionCascade}
 * is the only implementation today; this is the seam
 * docs/extraction-architecture.md's dedicated extraction service (Part D)
 * would later implement independently — {@code ProcessSaveHandler} depends on
 * this interface, not on {@code YtDlpClient}/{@code RapidYtClient} directly,
 * so swapping the implementation in behind it costs no change there.
 */
public interface SourceExtractor {

    /**
     * @param saveId needed only so a vision escalation inside the visual tier
     *               can attribute its request in {@code gemini_calls}
     */
    ExtractionCascade.Extraction extractFromUrl(String url, UUID saveId);
}
