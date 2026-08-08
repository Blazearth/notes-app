package com.weavr.api.save.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code PATCH /v1/saves/{id}/note} — edits the title and body of a
 * typed text note in place. Either field may be {@code null} to clear it;
 * omitting both is a no-op. Only valid for saves with {@code sourceType = TEXT}.
 */
public record UpdateNoteRequest(
        @Size(max = 500)  String title,
        @Size(max = 100_000) String body
) {}
