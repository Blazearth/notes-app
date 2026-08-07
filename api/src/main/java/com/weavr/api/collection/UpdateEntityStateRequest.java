package com.weavr.api.collection;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /v1/entity-state}.
 *
 * <p>Path-in-body rather than path-in-URL, the same reasoning as
 * {@code UpdateItemStateRequest}: an entity key like {@code "screen:blue box"}
 * carries characters that would need URL-encoding as a path segment.
 *
 * @param entityKey {@link Entities#key}'s output
 * @param state     the full state for that entity, e.g. {@code {"done": true,
 *                  "rating": 4}}. Always a full replace, never merged into
 *                  what was there — the same replace-don't-accumulate rule as
 *                  the shopping list and {@code save_item_states}.
 */
record UpdateEntityStateRequest(
        @NotNull String entityKey,
        @NotNull Map<String, Object> state
) {
}
