package com.weavr.api.collection;

import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /v1/entity-state} — the one write K2 adds beyond the derived
 * reads. A separate top-level controller rather than a method on
 * {@link CollectionController}, whose {@code @RequestMapping("/v1/collections")}
 * would otherwise force this under {@code /v1/collections/entity-state} —
 * see {@code docs/knowledge-collections.md} ("API surface") for the path as
 * specified.
 */
@RestController
@RequestMapping("/v1/entity-state")
class EntityStateController {

    private final EntityStateService entityStates;

    EntityStateController(EntityStateService entityStates) {
        this.entityStates = entityStates;
    }

    /**
     * No access check beyond auth — see {@link EntityStateService#setState}.
     * Returns just the written state, not a wrapping resource: unlike
     * {@code PATCH /v1/saves/{id}/item-state}, there is no parent resource to
     * echo back.
     */
    @PatchMapping
    Map<String, Object> setState(@CurrentUser UUID userId, @Valid @RequestBody UpdateEntityStateRequest request) {
        return entityStates.setState(userId, request.entityKey(), request.state());
    }
}
