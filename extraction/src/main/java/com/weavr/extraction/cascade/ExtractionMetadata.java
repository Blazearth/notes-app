package com.weavr.extraction.cascade;

import java.util.ArrayList;
import java.util.List;

import com.weavr.extraction.ytdlp.SourceMetadata;

/**
 * The wire shape of {@code result.metadata} in the API contract
 * (docs/extraction-architecture.md Part D). A thin wrapper over
 * {@link SourceMetadata} plus the one field it doesn't carry — which platform
 * this came from, since the backend uses it for enrichment routing and the
 * probe result alone doesn't say.
 */
public record ExtractionMetadata(
        String platform,
        String sourceId,
        String title,
        String description,
        String uploader,
        Double durationSeconds,
        List<String> captionLanguages,
        String pinnedComment
) {

    public static ExtractionMetadata from(String platform, SourceMetadata metadata) {
        return new ExtractionMetadata(platform, metadata.id(), metadata.title(), metadata.description(),
                metadata.uploader(), metadata.durationSeconds(), captionLanguages(metadata), metadata.pinnedComment());
    }

    public static ExtractionMetadata empty(String platform, String title) {
        return new ExtractionMetadata(platform, null, title, null, null, null, List.of(), null);
    }

    /** Uploaded and auto-generated language codes, deduplicated, uploaded first. */
    private static List<String> captionLanguages(SourceMetadata metadata) {
        List<String> merged = new ArrayList<>(metadata.captionLanguages());
        for (String code : metadata.autoCaptionLanguages()) {
            if (!merged.contains(code)) {
                merged.add(code);
            }
        }
        return List.copyOf(merged);
    }
}
