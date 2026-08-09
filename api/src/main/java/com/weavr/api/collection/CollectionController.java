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

    /**
     * The Library's collections spine — the whole axis tree per type
     * (Recommendations → Anime → Romance), with distinct entity and source
     * counts at every level and no entity payload.
     */
    @GetMapping
    List<CollectionNode> list(@CurrentUser UUID userId) {
        return collections.listCollections(userId);
    }

    /**
     * The merged entity list for one <em>node</em>, each with every source's
     * own un-merged item and a rolled-up view.
     *
     * <p>{@code nodeId} is either a bare type ({@code recommendation_list}) or
     * a path into its tree ({@code recommendation_list~anime~romance}) — a
     * node id is one path segment by construction, since
     * {@code CollectionService.slug} strips the separator out of every facet
     * value, so both forms bind here without a second route. {@code facet}
     * remains K1's single-level narrowing and is translated into the
     * equivalent depth-1 node id.
     *
     * <p>An unknown or unwired node returns an empty list rather than 404:
     * collections are always scoped to the caller's own saves, so there is no
     * cross-user id here to distinguish "not yours" from "doesn't exist".
     */
    @GetMapping("/{nodeId}")
    List<CollectionEntity> entities(@CurrentUser UUID userId, @PathVariable String nodeId,
                                     @RequestParam(required = false) String facet) {
        return collections.entities(userId, nodeId, facet);
    }
}
