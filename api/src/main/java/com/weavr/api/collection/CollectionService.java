package com.weavr.api.collection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.common.KnowledgeFacets;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.SaveStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The derived merge — collections, upgraded from {@code GroupService}'s save
 * groups to merged <em>entities</em>. See {@code docs/knowledge-collections.md}
 * for the full design.
 *
 * <p>K1 wired shape 1 (item-bearing list types — {@code recommendation_list},
 * {@code itinerary}, {@code checklist}) only. K4 adds shape 2 (save-is-the-
 * entity types — {@code movie}, {@code book}, {@code place}, {@code product},
 * {@code recipe}, {@code github_repo}) as a <em>join</em> onto an entity a
 * shape-1 item already created — see {@link #shape2Occurrences} — never as a
 * standalone entity a shape-2 save could produce on its own; {@code GET
 * /v1/collections/movie} still returns nothing, exactly as K1 left it. Shape
 * 3 (synthesis types — {@code workout}, {@code course}) stays entirely out of
 * scope, per the doc's own instruction not to force the metaphor onto them.
 *
 * <p><b>No table, no migration, no extra model call</b> for the merge itself
 * — exactly {@code GroupService}'s three reasons: a stored tree needs
 * invalidating on every classify/enrich/delete, the grouping signal was
 * already produced by the one classify call each save gets, and adding a
 * merged field is a change to {@link Entities} or the per-type shape below,
 * never a migration. K4's one addition, {@link CollectionOverrides} (manual
 * merge, rename), <em>is</em> stored — it is exactly the thing constraint 3
 * says can't be derived: the user's own curation.
 */
@Service
public class CollectionService {

    /** Separates the type segment from the facet segment in a node id — same scheme as {@code GroupService}. */
    private static final String ID_SEPARATOR = "~";

    /**
     * Minimum number of distinct entities that must share a facet value before
     * it gets its own subgroup — {@code GroupService.MIN_GROUP_SIZE}'s
     * counterpart, same reasoning: a subgroup of one entity is noise, not a
     * folder.
     */
    private static final int MIN_GROUP_SIZE = 5;

    /**
     * Which knowledge types are shape 1 (item-bearing lists) and how to read
     * an item out of one — the entity's name field, its per-item kind field
     * (nullable — checklist items carry no kind), and the fixed kind to use
     * when there is none. Every other item field is rolled up generically by
     * {@link #rollupFields}, so a new registry field on any of these three
     * types needs no change here.
     */
    private record ItemShape(String itemsField, String nameField, String kindField, String fixedKind) {}

    private static final Map<String, ItemShape> ITEM_SHAPES = Map.of(
            "recommendation_list", new ItemShape("items", "name", "kind", null),
            "itinerary", new ItemShape("places", "name", "kind", null),
            "checklist", new ItemShape("items", "text", null, "task"));

    /**
     * K4, shape 2: a save-is-the-entity type's own name field and the fixed
     * kind its saves always carry (there is no per-item kind field — the
     * whole save is one thing). See {@code docs/knowledge-collections.md}
     * ("Which types merge, and how", shape 2).
     */
    private record Shape2Def(String nameField, String fixedKind) {}

    private static final Map<String, Shape2Def> SHAPE2_TYPES = Map.ofEntries(
            Map.entry("movie", new Shape2Def("title", "movie")),
            Map.entry("book", new Shape2Def("title", "book")),
            Map.entry("place", new Shape2Def("name", "place")),
            Map.entry("product", new Shape2Def("title", "product")),
            Map.entry("recipe", new Shape2Def("title", "recipe")),
            Map.entry("github_repo", new Shape2Def("name", "github_repo")));

    /**
     * The only thing the merge needs from a save — {@code GroupService
     * .SaveFacts} plus {@code createdAt}, which the "most common surface
     * form, ties to the earliest save" rule needs and groups never did.
     */
    public record SaveFacts(UUID id, String knowledgeType, Map<String, Object> structuredData, Instant createdAt) {}

    /** One item's appearance in one save, before merging collapses same-key occurrences together. */
    private record Occurrence(UUID saveId, Instant savedAt, String rawName, Map<String, Object> item) {}

    private record TypeIndex(
            Map<String, List<Occurrence>> byEntity,
            Map<String, LinkedHashSet<String>> entityKeysByFacet,
            LinkedHashSet<String> looseEntityKeys) {}

    private final SaveRepository saves;
    private final EntityStateService entityStates;
    private final CollectionOverrideService overrides;

    CollectionService(SaveRepository saves, EntityStateService entityStates, CollectionOverrideService overrides) {
        this.saves = saves;
        this.entityStates = entityStates;
        this.overrides = overrides;
    }

    /**
     * K2: the pure tree, then a second pass filling in each node's
     * {@code doneCount} from the caller's own entity state — one batched
     * query for every entity key in the tree, not one per node.
     */
    @Transactional(readOnly = true)
    public List<CollectionNode> listCollections(UUID userId) {
        List<CollectionNode> tree = buildTree(loadReady(userId), MIN_GROUP_SIZE, overrides.loadFor(userId));
        Set<String> allKeys = new LinkedHashSet<>();
        tree.forEach(node -> allKeys.addAll(node.allEntityKeys()));
        Set<String> doneKeys = doneEntityKeys(userId, allKeys);
        return tree.stream().map(node -> node.withDoneCount(doneKeys)).toList();
    }

    /**
     * The merged entity list for one type, optionally filtered to a facet
     * value, each entity's K2 {@code state} joined in. Viewer-scoped like
     * every other read here — {@link #loadReady} only ever sees the caller's
     * own saves, so there is no cross-user id to leak the way {@code
     * relatedTo} guards against for a Space-shared save.
     */
    @Transactional(readOnly = true)
    public List<CollectionEntity> entities(UUID userId, String type, String facet) {
        List<CollectionEntity> merged = mergeType(loadReady(userId), type, facet, overrides.loadFor(userId));
        if (merged.isEmpty()) {
            return merged;
        }
        Map<String, Map<String, Object>> states =
                entityStates.statesFor(userId, merged.stream().map(CollectionEntity::entityKey).toList());
        return merged.stream()
                .map(entity -> states.containsKey(entity.entityKey())
                        ? new CollectionEntity(entity.entityKey(), entity.name(), entity.kind(), entity.fields(),
                                entity.sources(), entity.sourceCount(), states.get(entity.entityKey()))
                        : entity)
                .toList();
    }

    /** Which of the given entity keys the caller has marked {@code done: true}. */
    private Set<String> doneEntityKeys(UUID userId, Set<String> entityKeys) {
        if (entityKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> done = new LinkedHashSet<>();
        entityStates.statesFor(userId, entityKeys)
                .forEach((key, state) -> {
                    if (Boolean.TRUE.equals(state.get("done"))) done.add(key);
                });
        return done;
    }

    private List<SaveFacts> loadReady(UUID userId) {
        return saves.findByUserIdAndStatusOrderByCreatedAtDesc(userId, SaveStatus.READY).stream()
                .map(save -> new SaveFacts(save.getId(), save.getKnowledgeType(), save.getStructuredData(),
                        save.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ pure core

    /** Pure: no database, no Spring, no clock. */
    public static List<CollectionNode> buildTree(List<SaveFacts> ready) {
        return buildTree(ready, MIN_GROUP_SIZE, CollectionOverrides.EMPTY);
    }

    /** Overload used by tests to bypass the production threshold, with no overrides. */
    static List<CollectionNode> buildTree(List<SaveFacts> ready, int minGroupSize) {
        return buildTree(ready, minGroupSize, CollectionOverrides.EMPTY);
    }

    /**
     * The full pure builder: K1's tree, K4's shape-2 join and manual
     * overrides both applied. Still pure — {@code overrides} is data, not a
     * service reference, so a test can hand it a fixture the same way it
     * hands one a list of saves.
     */
    static List<CollectionNode> buildTree(List<SaveFacts> ready, int minGroupSize, CollectionOverrides overrides) {
        Map<String, List<SaveFacts>> byType = new LinkedHashMap<>();
        for (SaveFacts save : ready) {
            String type = normaliseType(save.knowledgeType());
            // Only the three item-bearing list types get their own top-level
            // node — shape 2's save-is-the-entity types never do (they only
            // ever join an existing shape-1 entity, see shape2Occurrences),
            // shape 3's synthesis types and unknown types keep today's
            // save-centric presentation.
            if (type == null || !ITEM_SHAPES.containsKey(type)) continue;
            byType.computeIfAbsent(type, key -> new ArrayList<>()).add(save);
        }

        Map<String, List<Occurrence>> shape2ByKey = shape2Occurrences(ready, overrides);
        List<CollectionNode> nodes = new ArrayList<>();
        byType.forEach((type, typeSaves) ->
                nodes.add(buildTypeNode(type, typeSaves, minGroupSize, shape2ByKey, overrides)));
        return nodes;
    }

    /**
     * Pure: the entities behind {@code GET /v1/collections/{type}}, no
     * overrides applied. Reusable standalone of {@link #buildTree} because a
     * caller who already knows the type shouldn't have to derive every other
     * type's tree to reach it.
     */
    static List<CollectionEntity> mergeType(List<SaveFacts> ready, String type, String facet) {
        return mergeType(ready, type, facet, CollectionOverrides.EMPTY);
    }

    /** The full pure entity list for one type — K4's shape-2 join and overrides both applied. */
    static List<CollectionEntity> mergeType(List<SaveFacts> ready, String type, String facet, CollectionOverrides overrides) {
        String normalizedType = normaliseType(type);
        if (normalizedType == null || !ITEM_SHAPES.containsKey(normalizedType)) {
            // Shape 2 types (movie, place, ...) are deliberately not wired here
            // either — they only ever attach to an entity a shape-1 type
            // already produced, never stand alone as their own type node.
            return List.of();
        }

        List<SaveFacts> typeSaves = ready.stream()
                .filter(save -> normalizedType.equals(normaliseType(save.knowledgeType())))
                .toList();

        Map<String, List<Occurrence>> shape2ByKey = shape2Occurrences(ready, overrides);
        TypeIndex idx = index(normalizedType, typeSaves, shape2ByKey, overrides);
        Map<String, CollectionEntity> merged = mergeAll(idx.byEntity(), ITEM_SHAPES.get(normalizedType), overrides);

        if (facet == null || facet.isBlank()) {
            return List.copyOf(merged.values());
        }
        return idx.entityKeysByFacet().entrySet().stream()
                .filter(entry -> slug(entry.getKey()).equals(slug(facet)))
                .flatMap(entry -> entry.getValue().stream())
                .distinct()
                .map(merged::get)
                .toList();
    }

    private static CollectionNode buildTypeNode(String type, List<SaveFacts> typeSaves, int minGroupSize,
                                                 Map<String, List<Occurrence>> shape2ByKey, CollectionOverrides overrides) {
        TypeIndex idx = index(type, typeSaves, shape2ByKey, overrides);
        Map<String, CollectionEntity> merged = mergeAll(idx.byEntity(), ITEM_SHAPES.get(type), overrides);

        List<CollectionNode> subgroups = new ArrayList<>();
        LinkedHashSet<String> loose = new LinkedHashSet<>(idx.looseEntityKeys());

        idx.entityKeysByFacet().forEach((facetValue, keys) -> {
            if (keys.size() >= minGroupSize) {
                String id = type + ID_SEPARATOR + slug(facetValue);
                subgroups.add(CollectionNode.of(
                        id,
                        overrides.collectionNames().getOrDefault(id, KnowledgeFacets.titleCase(facetValue)),
                        null,
                        List.of(),
                        List.copyOf(keys),
                        saveIdsFor(keys, merged)));
            } else {
                // Too few entities share this facet value — fold them into
                // loose so they still surface under the parent type, not in a
                // solo subgroup that is just noise.
                loose.addAll(keys);
            }
        });

        return CollectionNode.of(type,
                overrides.collectionNames().getOrDefault(type, KnowledgeFacets.displayName(type)), null, subgroups,
                List.copyOf(loose), saveIdsFor(loose, merged));
    }

    /**
     * Walks every save's items once, bucketing occurrences by entity key and,
     * separately, by the save's own facet value — so a type's items and its
     * facet membership are both derived in one pass. K4 folds in two more
     * things at this same point, both keyed by the same entity key space:
     * any manual-merge redirect ({@code overrides.resolve}), and any
     * shape-2 save that independently computed the same (post-redirect) key.
     */
    private static TypeIndex index(String type, List<SaveFacts> typeSaves,
                                    Map<String, List<Occurrence>> shape2ByKey, CollectionOverrides overrides) {
        ItemShape shape = ITEM_SHAPES.get(type);
        String facetField = KnowledgeFacets.FACETS.get(type);

        Map<String, List<Occurrence>> byEntity = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> entityKeysByFacet = new LinkedHashMap<>();
        LinkedHashSet<String> loose = new LinkedHashSet<>();

        for (SaveFacts save : typeSaves) {
            List<Map<String, Object>> items = itemsOf(save, shape.itemsField());
            String facetValue = facetField == null ? null : singleFacetValue(save, facetField);

            for (Map<String, Object> item : items) {
                Object rawNameValue = item.get(shape.nameField());
                if (!(rawNameValue instanceof String rawName) || !isUsable(rawName)) {
                    // No identity to merge on — dropping the item's own
                    // {@code reason}/{@code detail} would invent nothing, but
                    // keeping it with no name gives it nothing to merge on
                    // either, so it is skipped the same way an unusable facet
                    // value is in GroupService.
                    continue;
                }
                String kindForKey = shape.kindField() != null ? asString(item.get(shape.kindField())) : shape.fixedKind();
                String canonicalId = asString(item.get("tmdbId"));
                String entityKey = overrides.resolve(Entities.key(kindForKey, rawName, canonicalId));

                byEntity.computeIfAbsent(entityKey, key -> new ArrayList<>())
                        .add(new Occurrence(save.id(), save.createdAt(), rawName.trim(), item));

                if (facetValue != null) {
                    entityKeysByFacet.computeIfAbsent(facetValue, key -> new LinkedHashSet<>()).add(entityKey);
                } else {
                    loose.add(entityKey);
                }
            }
        }

        // K4, shape 2: a movie/place/... save never creates an entity of its
        // own here — it only ever attaches as an additional source on a key a
        // shape-1 item already put in byEntity, per "the review attaching to
        // the watchlist entry" (docs/knowledge-collections.md). A shape-2 save
        // whose key nothing above produced contributes nothing and is left as
        // an ordinary, individually-presented save — unchanged from today.
        shape2ByKey.forEach((key, occurrences) -> {
            List<Occurrence> existing = byEntity.get(key);
            if (existing != null) existing.addAll(occurrences);
        });

        return new TypeIndex(byEntity, entityKeysByFacet, loose);
    }

    /**
     * K4, shape 2: every ready save of a save-is-the-entity type
     * ({@link #SHAPE2_TYPES}), reduced to a single synthetic "occurrence" of
     * itself — the save's own {@code structuredData} minus its name field,
     * keyed the same way a shape-1 item is (canonical id preferred, manual
     * merge applied). Computed once over the <em>whole</em> ready list, not
     * per shape-1 type, because a movie save's own {@code knowledgeType}
     * never matches the shape-1 type it might join.
     */
    private static Map<String, List<Occurrence>> shape2Occurrences(List<SaveFacts> ready, CollectionOverrides overrides) {
        Map<String, List<Occurrence>> byEntity = new LinkedHashMap<>();
        for (SaveFacts save : ready) {
            String type = normaliseType(save.knowledgeType());
            Shape2Def def = type == null ? null : SHAPE2_TYPES.get(type);
            if (def == null) continue;

            Object rawNameValue = save.structuredData().get(def.nameField());
            if (!(rawNameValue instanceof String rawName) || !isUsable(rawName)) continue;

            String canonicalId = asString(save.structuredData().get("tmdbId"));
            String entityKey = overrides.resolve(Entities.key(def.fixedKind(), rawName, canonicalId));

            Map<String, Object> item = new LinkedHashMap<>(save.structuredData());
            item.remove(def.nameField());

            byEntity.computeIfAbsent(entityKey, key -> new ArrayList<>())
                    .add(new Occurrence(save.id(), save.createdAt(), rawName.trim(), item));
        }
        return byEntity;
    }

    private static Map<String, CollectionEntity> mergeAll(Map<String, List<Occurrence>> byEntity, ItemShape shape,
                                                            CollectionOverrides overrides) {
        Map<String, CollectionEntity> merged = new LinkedHashMap<>();
        byEntity.forEach((key, occurrences) -> merged.put(key, mergeEntity(key, occurrences, shape, overrides)));
        return merged;
    }

    /** One entity key's occurrences, collapsed into the entity {@link #entities} and {@link #listCollections} both return. */
    private static CollectionEntity mergeEntity(String entityKey, List<Occurrence> occurrences, ItemShape shape,
                                                 CollectionOverrides overrides) {
        String name = overrides.entityNames().getOrDefault(entityKey, resolveName(occurrences));
        String kind = resolveKind(occurrences, shape.kindField(), shape.fixedKind());
        Map<String, Object> fields = rollupFields(occurrences, shape.nameField(), shape.kindField());
        List<CollectionEntity.Source> sources = resolveSources(occurrences);
        // state is null here — the pure merge core has no database. Joined in
        // by CollectionService.entities(), the only caller with access to
        // EntityStateService.
        return new CollectionEntity(entityKey, name, kind, fields, sources, sources.size(), null);
    }

    /** The most common surface form across sources; ties go to the earliest save. */
    private static String resolveName(List<Occurrence> occurrences) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Instant> earliestBySurfaceForm = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            counts.merge(occurrence.rawName(), 1, Integer::sum);
            earliestBySurfaceForm.merge(occurrence.rawName(), occurrence.savedAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        String best = null;
        for (String candidate : counts.keySet()) {
            if (best == null
                    || counts.get(candidate) > counts.get(best)
                    || (counts.get(candidate).equals(counts.get(best))
                        && earliestBySurfaceForm.get(candidate).isBefore(earliestBySurfaceForm.get(best)))) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * First non-{@code [unclear]} kind across sources — for a shape with no
     * per-item kind field (checklist), the fixed kind every item of that type
     * shares.
     */
    private static String resolveKind(List<Occurrence> occurrences, String kindField, String fixedKind) {
        if (kindField == null) return fixedKind;
        for (Occurrence occurrence : occurrences) {
            String value = asString(occurrence.item().get(kindField));
            if (isUsable(value)) return value.trim();
        }
        return "[unclear]";
    }

    /**
     * Every item field but name and kind, rolled up: a list field is the
     * union across sources (this is where {@code genre} unions, with no
     * field-name-specific code); a scalar field is the first
     * non-{@code [unclear]} value, or {@code [unclear]} itself if every
     * source was — never invented, exactly the sentinel contract the registry
     * already promises.
     */
    private static Map<String, Object> rollupFields(List<Occurrence> occurrences, String nameField, String kindField) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Occurrence occurrence : occurrences) {
            for (String key : occurrence.item().keySet()) {
                if (!key.equals(nameField) && !key.equals(kindField)) keys.add(key);
            }
        }

        Map<String, Object> rolled = new LinkedHashMap<>();
        for (String key : keys) {
            boolean isListField = occurrences.stream().anyMatch(o -> o.item().get(key) instanceof Collection);
            rolled.put(key, isListField ? rollupList(occurrences, key) : rollupScalar(occurrences, key));
        }
        return rolled;
    }

    private static List<Object> rollupList(List<Occurrence> occurrences, String key) {
        LinkedHashSet<Object> union = new LinkedHashSet<>();
        for (Occurrence occurrence : occurrences) {
            Object value = occurrence.item().get(key);
            if (!(value instanceof Collection<?> collection)) continue;
            for (Object element : collection) {
                if (element instanceof String s ? isUsable(s) : element != null) union.add(element);
            }
        }
        return List.copyOf(union);
    }

    private static Object rollupScalar(List<Occurrence> occurrences, String key) {
        for (Occurrence occurrence : occurrences) {
            String value = asString(occurrence.item().get(key));
            if (isUsable(value)) return value.trim();
        }
        return "[unclear]";
    }

    /** One {@link CollectionEntity.Source} per distinct save — a save mentioning the same entity twice still counts once. */
    private static List<CollectionEntity.Source> resolveSources(List<Occurrence> occurrences) {
        Map<UUID, CollectionEntity.Source> bySave = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            bySave.putIfAbsent(occurrence.saveId(),
                    new CollectionEntity.Source(occurrence.saveId(), occurrence.savedAt(), occurrence.item()));
        }
        return List.copyOf(bySave.values());
    }

    private static List<UUID> saveIdsFor(Collection<String> entityKeys, Map<String, CollectionEntity> merged) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String key : entityKeys) {
            CollectionEntity entity = merged.get(key);
            if (entity != null) entity.sources().forEach(source -> ids.add(source.saveId()));
        }
        return List.copyOf(ids);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(SaveFacts save, String itemsField) {
        Object raw = save.structuredData().get(itemsField);
        if (!(raw instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : collection) {
            if (element instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
        }
        return out;
    }

    private static String singleFacetValue(SaveFacts save, String field) {
        Object raw = save.structuredData().get(field);
        return raw instanceof String s && isUsable(s) ? s.trim() : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /**
     * {@code [unclear]} is the registry's sentinel for "the model could not
     * tell" — the same reason {@code GroupService} won't group by it.
     */
    private static boolean isUsable(String value) {
        String trimmed = value == null ? "" : value.trim();
        return !trimmed.isEmpty() && !"[unclear]".equalsIgnoreCase(trimmed);
    }

    private static String normaliseType(String type) {
        if (type == null || type.isBlank()) return null;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /** URL-safe and stable — same scheme as {@code GroupService.slug}. */
    private static String slug(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
