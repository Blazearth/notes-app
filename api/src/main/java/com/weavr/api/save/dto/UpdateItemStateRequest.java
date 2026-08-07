package com.weavr.api.save.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /v1/saves/{id}/item-state}.
 *
 * <p>Path-in-body rather than path-in-URL ({@code PUT .../items/{path}/state})
 * because {@code itemPath} values like {@code "exercises[2]"} carry characters
 * that would need URL-encoding, and every other sub-resource mutation on this
 * controller (flags, lifecycle, space) already puts everything but the save id
 * in the body.
 *
 * @param itemPath identifies a position inside {@code structured_data} —
 *                 {@code "exercises[2]"}, {@code "items[0]"} — or {@code ""}
 *                 for state about the save as a whole
 * @param state    the full state for that item, e.g. {@code {"done": true}}.
 *                 Always a full replace, never merged into what was there —
 *                 the same replace-don't-accumulate rule as the shopping list
 */
public record UpdateItemStateRequest(
        @NotNull String itemPath,
        @NotNull Map<String, Object> state
) {
}
