package com.weavr.api.save.dto;

import com.weavr.api.save.LifecycleStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /v1/saves/{id}/lifecycle}.
 *
 * <p>An unknown value is rejected by {@link LifecycleStatus#fromDb} during
 * deserialisation, which surfaces as a 400 rather than a 500 — see
 * {@code ApiExceptionHandler}'s unreadable-body handler.
 */
public record UpdateLifecycleRequest(
        @NotNull(message = "lifecycleStatus is required")
        LifecycleStatus lifecycleStatus
) {
}
