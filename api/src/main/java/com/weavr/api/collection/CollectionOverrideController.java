package com.weavr.api.collection;

import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * K4's curation writes — manual entity merge/unmerge, entity rename,
 * collection rename. A separate top-level controller rather than methods on
 * {@link CollectionController}, the same reasoning {@code EntityStateController}
 * gives for its own separation: entity and collection keys need URL-encoding
 * as path segments, so every write here is body-over-path.
 */
@RestController
@RequestMapping("/v1/collection-overrides")
class CollectionOverrideController {

    private final CollectionOverrideService overrides;

    CollectionOverrideController(CollectionOverrideService overrides) {
        this.overrides = overrides;
    }

    record MergeRequest(@NotBlank String fromKey, @NotBlank String intoKey) {
    }

    record UnmergeRequest(@NotBlank String fromKey) {
    }

    record RenameEntityRequest(@NotBlank String entityKey, @NotBlank String name) {
    }

    record RenameCollectionRequest(@NotBlank String collectionId, @NotBlank String name) {
    }

    @PostMapping("/merge")
    ResponseEntity<Void> merge(@CurrentUser UUID userId, @Valid @RequestBody MergeRequest request) {
        overrides.merge(userId, request.fromKey(), request.intoKey());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unmerge")
    ResponseEntity<Void> unmerge(@CurrentUser UUID userId, @Valid @RequestBody UnmergeRequest request) {
        overrides.unmerge(userId, request.fromKey());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/entity-name")
    ResponseEntity<Void> renameEntity(@CurrentUser UUID userId, @Valid @RequestBody RenameEntityRequest request) {
        overrides.renameEntity(userId, request.entityKey(), request.name());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/collection-name")
    ResponseEntity<Void> renameCollection(@CurrentUser UUID userId, @Valid @RequestBody RenameCollectionRequest request) {
        overrides.renameCollection(userId, request.collectionId(), request.name());
        return ResponseEntity.noContent().build();
    }
}
