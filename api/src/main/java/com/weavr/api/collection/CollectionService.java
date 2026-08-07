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
 * for the full design; this is phase K1: only shape 1 (item-bearing list
 * types — {@code recommendation_list}, {@code itinerary}, {@code checklist})
 * is wired. Shape 2 (save-is-the-entity types) and shape 3 (synthesis types)
 * are explicitly out of scope here — everything else keeps today's
 * save-centric presentation.
 *
 * <p><b>No table, no migration, no extra model call</b> — exactly
 * {@code GroupService}'s three reasons: a stored tree needs invalidating on
 * every classify/enrich/delete, the grouping signal was already produced by
 * the one classify call each save gets, and adding a merged field is a change
 * to {@link Entities} or the per-type shape below, never a migration.
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

    CollectionService(SaveRepository saves, EntityStateService entityStates) {
        this.saves = saves;
        this.entityStates = entityStates;
    }

    /**
     * K2: the pure tree, then a second pass filling in each node's
     * {@code doneCount} from the caller's own entity state — one batched
     * query for every entity key in the tree, not one per node.
     */
    @Transactional(readOnly = true)
    public List<CollectionNode> listCollections(UUID userId) {
        List<CollectionNode> tree = buildTree(loadReady(userId));
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
        List<CollectionEntity> merged = mergeType(loadReady(userId), type, facet);
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
        return buildTree(ready, MIN_GROUP_SIZE);
    }

    /** Overload used by tests to bypass the production threshold. */
    static List<CollectionNode> buildTree(List<SaveFacts> ready, int minGroupSize) {
        Map<String, List<SaveFacts>> byType = new LinkedHashMap<>();
        for (SaveFacts save : ready) {
            String type = normaliseType(save.knowledgeType());
            // Only the three item-bearing list types are wired in K1 — everything
            // else (shape 2's save-is-the-entity types, shape 3's synthesis types,
            // and unknown types) keeps its today's save-centric presentation.
            if (type == null || !ITEM_SHAPES.containsKey(type)) continue;
            byType.computeIfAbsent(type, key -> new ArrayList<>()).add(save);
        }

        List<CollectionNode> nodes = new ArrayList<>();
        byType.forEach((type, typeSaves) -> nodes.add(buildTypeNode(type, typeSaves, minGroupSize)));
        return nodes;
    }

    /**
     * Pure: the entities behind {@code GET /v1/collections/{type}}. Reusable
     * standalone of {@link #buildTree} because a caller who already knows the
     * type shouldn't have to derive every other type's tree to reach it.
     */
    static List<CollectionEntity> mergeType(List<SaveFacts> ready, String type, String facet) {
        String normalizedType = normaliseType(type);
        if (normalizedType == null || !ITEM_SHAPES.containsKey(normalizedType)) {
            return List.of();
        }

        List<SaveFacts> typeSaves = ready.stream()
                .filter(save -> normalizedType.equals(normaliseType(save.knowledgeType())))
                .toList();

        TypeIndex idx = index(normalizedType, typeSaves);
        Map<String, CollectionEntity> merged = mergeAll(idx.byEntity(), ITEM_SHAPES.get(normalizedType));

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

    private static CollectionNode buildTypeNode(String type, List<SaveFacts> typeSaves, int minGroupSize) {
        TypeIndex idx = index(type, typeSaves);
        Map<String, CollectionEntity> merged = mergeAll(idx.byEntity(), ITEM_SHAPES.get(type));

        List<CollectionNode> subgroups = new ArrayList<>();
        LinkedHashSet<String> loose = new LinkedHashSet<>(idx.looseEntityKeys());

        idx.entityKeysByFacet().forEach((facetValue, keys) -> {
            if (keys.size() >= minGroupSize) {
                subgroups.add(CollectionNode.of(
                        type + ID_SEPARATOR + slug(facetValue),
                        KnowledgeFacets.titleCase(facetValue),
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

        return CollectionNode.of(type, KnowledgeFacets.displayName(type), null, subgroups,
                List.copyOf(loose), saveIdsFor(loose, merged));
    }

    /**
     * Walks every save's items once, bucketing occurrences by entity key and,
     * separately, by the save's own facet value — so a type's items and its
     * facet membership are both derived in one pass.
     */
    private static TypeIndex index(String type, List<SaveFacts> typeSaves) {
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
                String entityKey = Entities.key(kindForKey, rawName);

                byEntity.computeIfAbsent(entityKey, key -> new ArrayList<>())
                        .add(new Occurrence(save.id(), save.createdAt(), rawName.trim(), item));

                if (facetValue != null) {
                    entityKeysByFacet.computeIfAbsent(facetValue, key -> new LinkedHashSet<>()).add(entityKey);
                } else {
                    loose.add(entityKey);
                }
            }
        }
        return new TypeIndex(byEntity, entityKeysByFacet, loose);
    }

    private static Map<String, CollectionEntity> mergeAll(Map<String, List<Occurrence>> byEntity, ItemShape shape) {
        Map<String, CollectionEntity> merged = new LinkedHashMap<>();
        byEntity.forEach((key, occurrences) -> merged.put(key, mergeEntity(key, occurrences, shape)));
        return merged;
    }

    /** One entity key's occurrences, collapsed into the entity {@link #entities} and {@link #listCollections} both return. */
    private static CollectionEntity mergeEntity(String entityKey, List<Occurrence> occurrences, ItemShape shape) {
        String name = resolveName(occurrences);
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
