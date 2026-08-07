package com.weavr.api.save.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.weavr.api.save.LifecycleStatus;
import com.weavr.api.save.Save;
import com.weavr.api.save.SaveStatus;
import com.weavr.api.save.SourceType;

/**
 * Feed and detail representation. The client renders a type-specific card from
 * {@code knowledgeType} + {@code structuredData}, so both are exposed raw
 * rather than flattened into fixed fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveResponse(
        UUID id,
        UUID spaceId,
        SourceType sourceType,
        String sourceUrl,
        SaveStatus status,
        String knowledgeType,
        Double confidence,
        Map<String, Object> structuredData,
        LifecycleStatus lifecycleStatus,
        String modelUsed,
        String thumbnailUrl,
        boolean favorite,
        boolean archived,
        String errorCode,
        String errorMessage,

        /**
         * The caller's own Phase 4 object-behavior state, keyed by
         * {@code itemPath} ({@code "exercises[2]"}, {@code ""} for
         * whole-save state) — omitted entirely rather than an empty map when
         * there is none, so a save nobody has touched costs no extra bytes.
         * Embedded here so the detail screen needs no second fetch; see
         * {@link com.weavr.api.save.SaveItemStateService}.
         */
        Map<String, Map<String, Object>> itemStates,

        Instant createdAt,
        Instant updatedAt
) {

    /** For callers that have no item-state lookup available (mostly tests). */
    public static SaveResponse from(Save save) {
        return from(save, null);
    }

    public static SaveResponse from(Save save, Map<String, Map<String, Object>> itemStates) {
        return new SaveResponse(
                save.getId(),
                save.getSpaceId(),
                save.getSourceType(),
                save.getSourceUrl(),
                save.getStatus(),
                save.getKnowledgeType(),
                save.getConfidence(),
                save.getStructuredData(),
                save.getLifecycleStatus(),
                save.getModelUsed(),
                save.getThumbnailUrl(),
                save.isFavorite(),
                save.isArchived(),
                save.getErrorCode(),
                save.getErrorMessage(),
                itemStates == null || itemStates.isEmpty() ? null : itemStates,
                save.getCreatedAt(),
                save.getUpdatedAt());
    }
}
