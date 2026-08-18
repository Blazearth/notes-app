package com.weavr.api.pipeline.ytdlp;

import java.util.List;

/**
 * What a single {@code --dump-single-json} probe tells us. No bytes of media are
 * transferred to produce this.
 *
 * @param captionLanguages languages with *uploaded* subtitles
 * @param autoCaptionLanguages languages with machine-generated captions
 * @param pinnedComment the creator's pinned comment on YouTube, or — when the
 *                       platform never flags one, as Instagram never does —
 *                       the top comment authored by the video's own uploader.
 *                       Often carries the real recipe/instructions in full
 *                       where the caption or description only summarises it
 *                       (confirmed against a real video: description said
 *                       "garlic, butter, chili flakes...", the pinned comment
 *                       had exact quantities and a numbered method). Null
 *                       when the source has no such comment, comments are
 *                       disabled, or the probe path (currently only plain
 *                       yt-dlp, not the RapidAPI fast path) doesn't fetch
 *                       comments.
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
     *
     * <p>The pinned comment is appended regardless of whether captions or
     * metadata carry the base text — it is additive, not a fallback, because it
     * has been observed to carry the *more* precise version of content the
     * caption/description already summarised. Tokens are cheap and requests are
     * scarce, so a second detailed source costs nothing worth avoiding.
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
