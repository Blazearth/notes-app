package com.weavr.api.save.dto;

import java.util.UUID;

import com.weavr.api.save.SourceType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the share extension posts. Kept deliberately tiny — the client's only
 * job is to hand off a URL fast and get out of the way, so this must never
 * carry media.
 *
 * @param sourceType what is being handed over
 * @param sourceUrl  the shared link, for {@link SourceType#URL}
 * @param text       typed text, a caption, or on-device OCR output
 * @param spaceId    optional target space; null means the user's private feed
 */
public record CreateSaveRequest(

        @NotNull SourceType sourceType,

        @Size(max = 2048) String sourceUrl,

        @Size(max = 100_000) String text,

        UUID spaceId
) {

    @AssertTrue(message = "sourceUrl is required when sourceType is 'url'")
    public boolean isUrlPresentWhenRequired() {
        return sourceType != SourceType.URL || hasText(sourceUrl);
    }

    @AssertTrue(message = "text is required when sourceType is 'text'")
    public boolean isTextPresentWhenRequired() {
        return sourceType != SourceType.TEXT || hasText(text);
    }

    /** Mirrors the {@code saves_has_content} check constraint. */
    @AssertTrue(message = "one of sourceUrl or text is required")
    public boolean hasContent() {
        return hasText(sourceUrl) || hasText(text);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
