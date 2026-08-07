package com.weavr.api.collection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A node in the collection tree — {@code GroupNode}'s counterpart, one level
 * further merged: a leaf holds distinct entity keys instead of save ids.
 *
 * <p>Deliberately lightweight, the same reason {@code GET /v1/groups} is
 * cheap: this is the summary tree ({@code GET /v1/collections}), not the
 * entity payload — sources, per-source reasons and everything else on
 * {@link CollectionEntity} live behind {@code GET /v1/collections/{type}},
 * fetched only once a type is opened.
 *
 * @param entityCount the whole subtree's distinct entities, not
 *                     {@code entityKeys.size()} — see {@link #of}
 * @param sourceCount the whole subtree's distinct saves feeding it
 * @param entityKeys  entity keys held directly at this level
 * @param saveIds     save ids held directly at this level
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionNode(
        String id,
        String name,
        String description,
        int entityCount,
        int sourceCount,
        List<CollectionNode> subgroups,
        List<String> entityKeys,
        List<UUID> saveIds) {

    /**
     * Counts <em>distinct</em> entities and saves in the subtree — the same
     * {@code GroupNode.of} rule, for the same reason: an entity or a save can
     * legitimately sit under more than one facet (an item whose source saves
     * disagree on {@code medium}), and summing children over-counts it. A
     * collection is asked how many things are in it, not how many places it
     * was filed.
     */
    public static CollectionNode of(String id, String name, String description,
                                     List<CollectionNode> subgroups,
                                     List<String> entityKeys, List<UUID> saveIds) {
        Set<String> distinctEntities = new LinkedHashSet<>(entityKeys);
        Set<UUID> distinctSaves = new LinkedHashSet<>(saveIds);
        subgroups.forEach(child -> collectInto(child, distinctEntities, distinctSaves));
        return new CollectionNode(id, name, description, distinctEntities.size(), distinctSaves.size(),
                subgroups, entityKeys, saveIds);
    }

    private static void collectInto(CollectionNode node, Set<String> entities, Set<UUID> saves) {
        entities.addAll(node.entityKeys());
        saves.addAll(node.saveIds());
        node.subgroups().forEach(child -> collectInto(child, entities, saves));
    }
}
