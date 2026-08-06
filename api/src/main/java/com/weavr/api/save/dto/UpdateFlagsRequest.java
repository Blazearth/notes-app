package com.weavr.api.save.dto;

/**
 * Body of {@code PATCH /v1/saves/{id}/flags}.
 *
 * <p>Both fields optional and applied only when present, so a swipe-to-favorite
 * action can send {@code {"favorite": true}} without touching {@code archived},
 * and vice versa — one endpoint for both flags rather than two nearly-identical
 * ones.
 */
public record UpdateFlagsRequest(
        Boolean favorite,
        Boolean archived
) {
}
