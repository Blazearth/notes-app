package com.weavr.api.group;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A node in the group tree — an intelligent folder.
 *
 * <p><b>Recursive by design.</b> {@code subgroups} holds nodes of this same
 * type rather than a separate leaf shape, so depth is unbounded by construction:
 * grouping recipes by cuisine and then by course is a change to
 * {@link GroupService}'s facet chain, not to this record, the controller, or the
 * client. The app already renders arbitrary depth from one screen.
 *
 * @param id          stable and URL-safe — see {@link GroupService} for the scheme
 * @param name        display name, already title-cased
 * @param itemCount   the whole subtree, not {@code saveIds.size()}
 * @param subgroups   empty for a leaf, never null
 * @param saveIds     saves held directly at this level
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupNode(
        String id,
        String name,
        String description,
        int itemCount,
        List<GroupNode> subgroups,
        List<UUID> saveIds) {

    /**
     * Counts <em>distinct</em> saves in the subtree.
     *
     * <p>Not a sum of the children's counts, which is the obvious version and
     * is wrong: a facet like {@code genre} is a list, so one film filed under
     * Thriller and Drama is two memberships and one save. Summing reported
     * "Watchlist · 2 items" over a library holding a single movie — caught by
     * running this against a real database, where the first response said
     * exactly that.
     *
     * <p>So the count answers "how many things are in here?", which is what a
     * folder is asked, rather than "how many places did we file them?", which
     * is an implementation detail leaking into the interface.
     */
    public static GroupNode of(String id, String name, String description,
                               List<GroupNode> subgroups, List<UUID> saveIds) {
        Set<UUID> distinct = new LinkedHashSet<>(saveIds);
        subgroups.forEach(child -> collectInto(child, distinct));
        return new GroupNode(id, name, description, distinct.size(), subgroups, saveIds);
    }

    private static void collectInto(GroupNode node, Set<UUID> out) {
        out.addAll(node.saveIds());
        node.subgroups().forEach(child -> collectInto(child, out));
    }
}
