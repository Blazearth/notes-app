package com.weavr.extraction.ytdlp;

import java.util.List;

/**
 * What a single {@code --dump-single-json} probe (or the RapidAPI equivalent)
 * tells us. No bytes of media are transferred to produce this. Ported
 * unchanged from {@code api}'s {@code SourceMetadata}.
 *
 * @param captionLanguages languages with *uploaded* subtitles
 * @param autoCaptionLanguages languages with machine-generated captions
 * @param pinnedComment the creator's pinned comment, if any
 */
public record SourceMetadata(
        String id,
        String title,
        String description,
        String uploader,
        Double durationSeconds,
        String thumbnailUrl,
        List<String> captionLanguages,
        List<String> autoCaptionLanguages,
        String pinnedComment
) {

    public boolean hasCaptions() {
        return !captionLanguages.isEmpty() || !autoCaptionLanguages.isEmpty();
    }

    /**
     * Text worth handing to the model even when no captions exist. A title plus
     * a rich description is frequently enough on its own, which is the whole
     * reason the cascade tries this before touching audio.
     */
    public String asText() {
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append(title.strip()).append('\n');
        }
        if (uploader != null && !uploader.isBlank()) {
            text.append("by ").append(uploader.strip()).append('\n');
        }
        if (description != null && !description.isBlank()) {
            text.append(description.strip());
        }
        if (pinnedComment != null && !pinnedComment.isBlank()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("Pinned comment:\n").append(pinnedComment.strip());
        }
        return text.toString().strip();
    }
}
