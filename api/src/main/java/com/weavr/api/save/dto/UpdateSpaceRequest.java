package com.weavr.api.save.dto;

import java.util.UUID;

/**
 * Body for {@code PATCH /v1/saves/{id}/space}.
 * A null {@code spaceId} removes the save from its current Space.
 */
public record UpdateSpaceRequest(UUID spaceId) {}
