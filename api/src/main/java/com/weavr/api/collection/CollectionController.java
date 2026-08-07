package com.weavr.api.collection;

import java.util.List;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collections — {@code GroupController}'s counterpart one level further
 * merged: entities instead of saves. Read-only and derived per request, same
 * reasoning as groups — nothing here is stored except (from K2 onward) each
 * entity's user state.
 */
@RestController
@RequestMapping("/v1/collections")
class CollectionController {

    private final CollectionService collections;

    CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    /** The Library's collections spine: type → facet, with distinct entity and source counts, no entity payload. */
    @GetMapping
    List<CollectionNode> list(@CurrentUser UUID userId) {
        return collections.listCollections(userId);
    }

    /**
     * The merged entity list for one type, each with every source's own
     * un-merged item and a rolled-up view. {@code facet} narrows to one of the
     * tree's subgroup values (matched loosely, the same slugging
     * {@link CollectionNode} ids use) — omitted, it returns every entity of
     * the type. An unknown or unwired {@code type} returns an empty list
     * rather than 404: collections are always scoped to the caller's own
     * saves, so there is no cross-user id here to distinguish "not yours"
     * from "doesn't exist".
     */
    @GetMapping("/{type}")
    List<CollectionEntity> entities(@CurrentUser UUID userId, @PathVariable String type,
                                     @RequestParam(required = false) String facet) {
        return collections.entities(userId, type, facet);
    }
}
