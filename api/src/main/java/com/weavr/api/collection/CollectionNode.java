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
 * @param doneCount   the whole subtree's distinct entities whose K2 state has
 *                     {@code done: true} — 0 from the pure {@link #of}
 *                     factory, which has no database; {@code CollectionService
 *                     .listCollections} fills it in with a second pass once
 *                     state is loaded. See {@code docs/knowledge-collections.md}
 *                     ("API surface")'s {@code {name, entityCount, doneCount,
 *                     sourceCount}} shape.
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
        int doneCount,
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
     *
     * <p>{@code doneCount} starts at 0 here — this factory is called from the
     * pure, database-free merge core, which has no state to count. {@link
     * CollectionService#listCollections} rebuilds the tree with the real
     * count once it has loaded state, via {@link #withDoneCount}.
     */
    public static CollectionNode of(String id, String name, String description,
                                     List<CollectionNode> subgroups,
                                     List<String> entityKeys, List<UUID> saveIds) {
        Set<String> distinctEntities = new LinkedHashSet<>(entityKeys);
        Set<UUID> distinctSaves = new LinkedHashSet<>(saveIds);
        subgroups.forEach(child -> collectInto(child, distinctEntities, distinctSaves));
        return new CollectionNode(id, name, description, distinctEntities.size(), 0, distinctSaves.size(),
                subgroups, entityKeys, saveIds);
    }

    /** Every distinct entity key anywhere in this subtree — used to compute {@code doneCount} against loaded state. */
    public Set<String> allEntityKeys() {
        Set<String> out = new LinkedHashSet<>();
        collectInto(this, out, new LinkedHashSet<>());
        return out;
    }

    /**
     * Rebuilds this subtree with {@code doneCount} filled in from a set of
     * entity keys already known to be done — the same distinct-not-summed
     * rule {@link #of} applies to {@code entityCount}, applied to a second
     * property computed from data {@link #of} never had.
     */
    public CollectionNode withDoneCount(Set<String> doneEntityKeys) {
        List<CollectionNode> rebuiltSubgroups = subgroups.stream()
                .map(child -> child.withDoneCount(doneEntityKeys))
                .toList();
        int done = (int) allEntityKeys().stream().filter(doneEntityKeys::contains).count();
        return new CollectionNode(id, name, description, entityCount, done, sourceCount,
                rebuiltSubgroups, entityKeys, saveIds);
    }

    private static void collectInto(CollectionNode node, Set<String> entities, Set<UUID> saves) {
        entities.addAll(node.entityKeys());
        saves.addAll(node.saveIds());
        node.subgroups().forEach(child -> collectInto(child, entities, saves));
    }
}
