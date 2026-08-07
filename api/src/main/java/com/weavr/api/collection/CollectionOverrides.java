package com.weavr.api.collection;

import java.util.Map;

/**
 * One user's curation over the derived collection view — K4's "identity
 * upgrades". See {@code docs/knowledge-collections.md} ("Stored state",
 * {@code V14__collection_overrides.sql}) for the design.
 *
 * <p>Threaded explicitly into the pure merge core ({@link CollectionService
 * #buildTree(java.util.List, int, CollectionOverrides)}, {@link
 * CollectionService#mergeType(java.util.List, String, String,
 * CollectionOverrides)}) rather than the core reaching into a service for
 * it — the merge core stays database-free and unit-testable exactly the way
 * K1 left it; only {@link CollectionOverrideService#loadFor} touches a
 * database, and only {@link CollectionService}'s instance methods call that.
 *
 * @param mergeRedirects   losing entity key → the key it now resolves as,
 *                         already fully chased through any chain and
 *                         cycle-guarded — see {@link CollectionOverrideService
 *                         #loadFor}. Applied at the point every occurrence's
 *                         entity key is computed, so it reads as if the two
 *                         entities had always shared one key.
 * @param entityNames      entity key (post-redirect) → the user's own name
 *                         for it, overriding {@link CollectionService
 *                         #mergeEntity}'s "most common surface form" pick.
 * @param collectionNames  a {@link CollectionNode} id (a type, or
 *                         {@code "type~facet-slug"}) → the user's own name
 *                         for that node.
 */
record CollectionOverrides(
        Map<String, String> mergeRedirects,
        Map<String, String> entityNames,
        Map<String, String> collectionNames) {

    static final CollectionOverrides EMPTY = new CollectionOverrides(Map.of(), Map.of(), Map.of());

    /** The key an occurrence should actually bucket under, after any manual merge. */
    String resolve(String rawEntityKey) {
        return mergeRedirects.getOrDefault(rawEntityKey, rawEntityKey);
    }
}
