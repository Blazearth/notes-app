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
import java.util.regex.Pattern;

import com.weavr.api.collection.CollectionAxes.CollectionAxis;
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
     * Which knowledge types are shape 1 (item-bearing lists) and how to read
     * an item out of one — the entity's name field, its per-item kind field
     * (nullable — checklist and workout items carry no kind), and the fixed
     * kind to use when there is none. Every other item field is rolled up
     * generically by {@link #rollupFields}, so a new registry field on any of
     * these types needs no change here.
     *
     * <p>{@code workout} joins here rather than staying shape 3: its
     * {@code exercises[]} <em>is</em> an item-bearing list, and merging it
     * gives "Bench Press, in 3 of your push days" — the same cross-source
     * aggregation every other entry produces. What stays out of scope is
     * synthesising a <em>new</em> routine from several saves, which is the
     * thing {@code docs/knowledge-collections.md} warns against forcing; a
     * merged exercise is a fact about the library, not an invented program.
     */
    private record ItemShape(String itemsField, String nameField, String kindField, String fixedKind) {}

    private static final Map<String, ItemShape> ITEM_SHAPES = Map.of(
            "recommendation_list", new ItemShape("items", "name", "kind", null),
            "itinerary", new ItemShape("places", "name", "kind", null),
            "checklist", new ItemShape("items", "text", null, "task"),
            "workout", new ItemShape("exercises", "name", null, "exercise"));

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
     *
     * @param ownerId who saved it. {@code null} on every personal read, where
     *                the answer is always "the caller" and attributing it would
     *                put the same name on every row; set only by S1's
     *                Space-scoped load, which is the one place several people's
     *                saves are merged together. See
     *                {@code docs/knowledge-spaces.md}.
     */
    public record SaveFacts(UUID id, String knowledgeType, Map<String, Object> structuredData, Instant createdAt,
                            UUID ownerId) {

        public SaveFacts(UUID id, String knowledgeType, Map<String, Object> structuredData, Instant createdAt) {
            this(id, knowledgeType, structuredData, createdAt, null);
        }
    }

    /** One item's appearance in one save, before merging collapses same-key occurrences together. */
    private record Occurrence(UUID saveId, Instant savedAt, String rawName, Map<String, Object> item, UUID ownerId) {}

    /**
     * One type's items, bucketed by entity key, plus — per entity — the
     * save-level field values every save that contributed it carried.
     *
     * <p>K1 kept a single {@code facet value → entity keys} map, which could
     * only ever express one level of grouping by one save-level field.
     * Recording the values <em>per entity</em> instead lets
     * {@link #buildLevel} partition the same set repeatedly, once per axis,
     * and lets a {@link CollectionAxes.Source#SAVE} axis sit at any depth
     * rather than only the first.
     */
    private record TypeIndex(
            Map<String, List<Occurrence>> byEntity,
            Map<String, Map<String, LinkedHashSet<String>>> saveFieldValues) {}

    /** One level's partition: the subgroups that met their threshold, and the keys left at this level. */
    private record Level(List<CollectionNode> subgroups, LinkedHashSet<String> looseEntityKeys) {}

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
        List<CollectionNode> tree = buildTree(loadReady(userId), null, overrides.loadFor(userId));
        Set<String> allKeys = new LinkedHashSet<>();
        tree.forEach(node -> allKeys.addAll(node.allEntityKeys()));
        Set<String> doneKeys = doneEntityKeys(userId, allKeys);
        return tree.stream().map(node -> node.withDoneCount(doneKeys)).toList();
    }

    /**
     * The merged entity list for one <em>node</em>, each entity's K2
     * {@code state} joined in. Viewer-scoped like every other read here —
     * {@link #loadReady} only ever sees the caller's own saves, so there is no
     * cross-user id to leak the way {@code relatedTo} guards against for a
     * Space-shared save.
     *
     * <p>{@code nodeId} is either a bare type ({@code recommendation_list} —
     * every entity of that type) or a path into its tree
     * ({@code recommendation_list~anime~romance} — the entities in that
     * subtree). Both are one path segment, so this needed no new route.
     * {@code facet} is K1's single-level narrowing, kept working by
     * translating it into the equivalent depth-1 node id.
     */
    @Transactional(readOnly = true)
    public List<CollectionEntity> entities(UUID userId, String nodeId, String facet) {
        List<CollectionEntity> merged =
                mergeNode(loadReady(userId), resolveNodeId(nodeId, facet), overrides.loadFor(userId));
        if (merged.isEmpty()) {
            return merged;
        }
        Map<String, Map<String, Object>> states =
                entityStates.statesFor(userId, merged.stream().map(CollectionEntity::entityKey).toList());
        return merged.stream()
                .map(entity -> states.containsKey(entity.entityKey())
                        ? withState(entity, states.get(entity.entityKey()))
                        : entity)
                .toList();
    }

    /** {@link CollectionEntity} is a record, so "with one field replaced" is a rebuild. */
    public static CollectionEntity withState(CollectionEntity entity, Map<String, Object> state) {
        return new CollectionEntity(entity.entityKey(), entity.name(), entity.kind(), entity.fields(),
                entity.sources(), entity.sourceCount(), state);
    }

    // ------------------------------------------------- S1: an arbitrary scope
    //
    // The Space-scoped endpoints (docs/knowledge-spaces.md, S1) are the same
    // derived merge over a different save set, which is why they are two
    // entry points here rather than a second implementation somewhere else.
    // Membership is checked by the caller — `SpaceKnowledgeService`, which owns
    // every other Space guard — before it ever loads a save set to pass in, so
    // by the time a list reaches these methods every save in it is already
    // visible to the reader and the merge introduces no new access question.
    //
    // Neither applies `CollectionOverrides`, deliberately: overrides are one
    // *user's* curation (`collection_overrides.user_id`), and reshaping a
    // shared view by one member's private renames and merges would show the
    // group something only that member had asked for. A per-Space override
    // table is the thing to build if this is ever wanted, not a silent reuse
    // of the personal one.

    /** S1: the collection tree over any save set — no user, no state, no overrides. */
    public static List<CollectionNode> treeOf(List<SaveFacts> ready) {
        return buildTree(ready, null, CollectionOverrides.EMPTY);
    }

    /** S1: the merged entities under one node of {@link #treeOf}'s tree. */
    public static List<CollectionEntity> entitiesOf(List<SaveFacts> ready, String nodeId, String facet) {
        return mergeNode(ready, resolveNodeId(nodeId, facet), CollectionOverrides.EMPTY);
    }

    /** K1's {@code ?facet=} narrowing is the depth-1 node under a type — one translation, two callers. */
    private static String resolveNodeId(String nodeId, String facet) {
        return facet == null || facet.isBlank() ? nodeId : nodeId + ID_SEPARATOR + slug(facet);
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
        return buildTree(ready, null, CollectionOverrides.EMPTY);
    }

    /**
     * Overload used by tests to bypass the per-axis thresholds, with no
     * overrides — {@code minGroupSize} here <em>replaces</em>
     * {@link CollectionAxes.CollectionAxis#minGroupSize()} at every level, so
     * a small fixture still produces the full tree.
     */
    static List<CollectionNode> buildTree(List<SaveFacts> ready, Integer minGroupSize) {
        return buildTree(ready, minGroupSize, CollectionOverrides.EMPTY);
    }

    /**
     * The full pure builder: the axis tree, K4's shape-2 join and manual
     * overrides all applied. Still pure — {@code overrides} is data, not a
     * service reference, so a test can hand it a fixture the same way it
     * hands one a list of saves. {@code minGroupSizeOverride} is null in
     * production, where each axis carries its own threshold.
     */
    static List<CollectionNode> buildTree(List<SaveFacts> ready, Integer minGroupSizeOverride,
                                           CollectionOverrides overrides) {
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
        byType.forEach((type, typeSaves) -> {
            CollectionNode node = buildTypeNode(type, typeSaves, minGroupSizeOverride, shape2ByKey, overrides);
            // A type whose saves carried no usable items — a workout save with
            // no `exercises`, a list whose every item name was `[unclear]` —
            // produces no entities, and an empty collection is worse than no
            // collection: the Library hides a type's individual saves once it
            // has a collection to show instead, so an empty node would hide
            // them behind nothing.
            if (node.entityCount() > 0) nodes.add(node);
        });
        return nodes;
    }

    /**
     * Pure: every merged entity of one type, no overrides applied. Reusable
     * standalone of {@link #buildTree} because a caller who already knows the
     * type shouldn't have to derive every other type's tree to reach it.
     */
    static List<CollectionEntity> mergeType(List<SaveFacts> ready, String type, String facet) {
        return mergeType(ready, type, facet, CollectionOverrides.EMPTY);
    }

    /**
     * K1's type-and-facet entry point, now a thin translation onto
     * {@link #mergeNode} — a facet is just the depth-1 node under a type.
     */
    static List<CollectionEntity> mergeType(List<SaveFacts> ready, String type, String facet,
                                             CollectionOverrides overrides) {
        String nodeId = facet == null || facet.isBlank() ? type : type + ID_SEPARATOR + slug(facet);
        return mergeNode(ready, nodeId, overrides);
    }

    /**
     * Pure: the merged entities under one node of the tree — a bare type id
     * for all of them, or a path like
     * {@code recommendation_list~anime~romance} for one subtree.
     *
     * <p>Resolved by <em>walking the axis path</em> — filtering the merged set
     * by each segment's axis value in turn — rather than by building the tree
     * and finding the node in it. Two reasons, both learned from the first
     * attempt at this:
     *
     * <ul>
     *   <li>A tree lookup makes the entity list depend on
     *       {@link CollectionAxes.CollectionAxis#minGroupSize()}: a subgroup
     *       too small to be worth <em>showing</em> would answer with nothing
     *       at all rather than with its entities, so a node id that is
     *       perfectly well-formed resolves empty.</li>
     *   <li>It costs a whole tree build to answer a question that is really
     *       just a filter.</li>
     * </ul>
     *
     * <p>Order follows the merge's own insertion order rather than the tree's,
     * so the same entity is listed consistently whichever node it is read
     * through.
     */
    static List<CollectionEntity> mergeNode(List<SaveFacts> ready, String nodeId, CollectionOverrides overrides) {
        String[] path = nodeId == null ? new String[0] : nodeId.split(Pattern.quote(ID_SEPARATOR));
        String normalizedType = path.length == 0 ? null : normaliseType(path[0]);
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

        List<CollectionAxis> axes = CollectionAxes.axesFor(normalizedType);
        Set<String> wanted = new LinkedHashSet<>(merged.keySet());
        for (int depth = 1; depth < path.length; depth++) {
            if (depth > axes.size()) {
                // The path is deeper than the type's axis chain — not a node
                // this type can ever produce.
                return List.of();
            }
            CollectionAxis axis = axes.get(depth - 1);
            String segment = path[depth];
            wanted.removeIf(key -> axisValues(axis, key, merged.get(key), idx).stream()
                    .noneMatch(value -> slug(value).equals(segment)));
        }

        return merged.entrySet().stream()
                .filter(entry -> wanted.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static CollectionNode buildTypeNode(String type, List<SaveFacts> typeSaves, Integer minGroupSizeOverride,
                                                 Map<String, List<Occurrence>> shape2ByKey, CollectionOverrides overrides) {
        TypeIndex idx = index(type, typeSaves, shape2ByKey, overrides);
        Map<String, CollectionEntity> merged = mergeAll(idx.byEntity(), ITEM_SHAPES.get(type), overrides);

        Level root = buildLevel(type, CollectionAxes.axesFor(type), 0, merged.keySet(), merged, idx,
                minGroupSizeOverride, overrides);

        return CollectionNode.of(type,
                overrides.collectionNames().getOrDefault(type, KnowledgeFacets.displayName(type)), null,
                root.subgroups(), List.copyOf(root.looseEntityKeys()),
                saveIdsFor(root.looseEntityKeys(), merged));
    }

    /**
     * Partitions one set of entity keys by the axis at {@code depth}, then
     * recurses into each surviving bucket with the next axis — the whole of
     * the multi-level grouping, in one function that knows nothing about any
     * particular type or field.
     *
     * <p>Two rules are load-bearing:
     *
     * <ul>
     *   <li><b>An entity may sit in several buckets at the same level</b> (a
     *       two-genre title), so buckets are built by adding, never by moving.
     *       {@link CollectionNode#of} then counts distinct entities across the
     *       subtree rather than summing children, which is what keeps
     *       "14 titles" from reading as 19.</li>
     *   <li><b>A key falls through to loose only if <em>no</em> bucket it
     *       landed in survived.</b> Deciding per bucket instead would list a
     *       title inside Romance <em>and</em> loose beside it, because its
     *       second genre happened to be rare.</li>
     * </ul>
     */
    private static Level buildLevel(String idPrefix, List<CollectionAxis> axes, int depth,
                                     Collection<String> entityKeys, Map<String, CollectionEntity> merged,
                                     TypeIndex idx, Integer minGroupSizeOverride, CollectionOverrides overrides) {
        if (depth >= axes.size() || entityKeys.isEmpty()) {
            return new Level(List.of(), new LinkedHashSet<>(entityKeys));
        }

        CollectionAxis axis = axes.get(depth);
        int threshold = minGroupSizeOverride != null ? minGroupSizeOverride : axis.minGroupSize();

        Map<String, LinkedHashSet<String>> buckets = new LinkedHashMap<>();
        LinkedHashSet<String> unbucketed = new LinkedHashSet<>();
        for (String key : entityKeys) {
            List<String> values = axisValues(axis, key, merged.get(key), idx);
            if (values.isEmpty()) {
                // No usable value on this axis — [unclear], absent, or an empty
                // array. The entity stays at this level rather than being filed
                // under a guess, the same sentinel contract as everywhere else.
                unbucketed.add(key);
                continue;
            }
            values.forEach(value -> buckets.computeIfAbsent(value, v -> new LinkedHashSet<>()).add(key));
        }

        List<CollectionNode> subgroups = new ArrayList<>();
        LinkedHashSet<String> claimed = new LinkedHashSet<>();
        for (Map.Entry<String, LinkedHashSet<String>> bucket : buckets.entrySet()) {
            if (bucket.getValue().size() < threshold) continue;
            String childId = idPrefix + ID_SEPARATOR + slug(bucket.getKey());
            Level child = buildLevel(childId, axes, depth + 1, bucket.getValue(), merged, idx,
                    minGroupSizeOverride, overrides);
            subgroups.add(CollectionNode.of(
                    childId,
                    overrides.collectionNames().getOrDefault(childId, axis.displayName().apply(bucket.getKey())),
                    null,
                    child.subgroups(),
                    List.copyOf(child.looseEntityKeys()),
                    saveIdsFor(child.looseEntityKeys(), merged)));
            claimed.addAll(bucket.getValue());
        }

        LinkedHashSet<String> loose = new LinkedHashSet<>(unbucketed);
        for (String key : entityKeys) {
            if (!claimed.contains(key)) loose.add(key);
        }
        return new Level(subgroups, loose);
    }

    /**
     * One entity's values on one axis, already derived and de-duplicated. A
     * {@link CollectionAxes.Source#SAVE} axis reads what the contributing
     * saves carried (recorded per entity by {@link #index}); a
     * {@link CollectionAxes.Source#ENTITY} axis reads the merged entity — its
     * resolved {@code kind}, or a rolled-up field, scalar or unioned list.
     */
    private static List<String> axisValues(CollectionAxis axis, String entityKey, CollectionEntity entity,
                                            TypeIndex idx) {
        if (entity == null) return List.of();
        Collection<String> raw = axis.from() == CollectionAxes.Source.SAVE
                ? idx.saveFieldValues().getOrDefault(entityKey, Map.of())
                        .getOrDefault(axis.field(), new LinkedHashSet<>())
                : entityFieldValues(entity, axis.field());
        return CollectionAxes.derivedValues(axis, raw);
    }

    private static List<String> entityFieldValues(CollectionEntity entity, String field) {
        if ("kind".equals(field)) {
            // Canonicalised, not just trimmed — bucketing by the raw extracted
            // spelling would file "movie" and "film" as two separate nodes
            // that both happen to display as "Movies". See
            // CollectionAxes.canonicalKind.
            return isUsable(entity.kind()) ? List.of(CollectionAxes.canonicalKind(entity.kind())) : List.of();
        }
        return usableStrings(entity.fields().get(field));
    }

    /** A scalar string or a collection of them, filtered to usable values — one reader for both shapes. */
    private static List<String> usableStrings(Object raw) {
        if (raw instanceof String s) {
            return isUsable(s) ? List.of(s.trim()) : List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> out = new ArrayList<>();
            for (Object element : collection) {
                if (element instanceof String s && isUsable(s)) out.add(s.trim());
            }
            return out;
        }
        return List.of();
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
        // Only the save-level axes need reading off the save; entity-level
        // axes are answered later, from the merged entity itself.
        List<String> saveAxisFields = CollectionAxes.axesFor(type).stream()
                .filter(axis -> axis.from() == CollectionAxes.Source.SAVE)
                .map(CollectionAxis::field)
                .distinct()
                .toList();

        Map<String, List<Occurrence>> byEntity = new LinkedHashMap<>();
        Map<String, Map<String, LinkedHashSet<String>>> saveFieldValues = new LinkedHashMap<>();

        for (SaveFacts save : typeSaves) {
            List<Map<String, Object>> items = itemsOf(save, shape.itemsField());
            Map<String, List<String>> axisValuesForSave = new LinkedHashMap<>();
            for (String field : saveAxisFields) {
                axisValuesForSave.put(field, usableStrings(save.structuredData().get(field)));
            }

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
                        .add(new Occurrence(save.id(), save.createdAt(), rawName.trim(), item, save.ownerId()));

                // The same entity reached by two saves accumulates both saves'
                // values, so a place named by a Japan itinerary and a Tokyo one
                // files under both rather than whichever landed last.
                Map<String, LinkedHashSet<String>> forEntity =
                        saveFieldValues.computeIfAbsent(entityKey, key -> new LinkedHashMap<>());
                axisValuesForSave.forEach((field, values) ->
                        forEntity.computeIfAbsent(field, f -> new LinkedHashSet<>()).addAll(values));
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

        return new TypeIndex(byEntity, saveFieldValues);
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
                    .add(new Occurrence(save.id(), save.createdAt(), rawName.trim(), item, save.ownerId()));
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
                    new CollectionEntity.Source(occurrence.saveId(), occurrence.savedAt(), occurrence.item(),
                            occurrence.ownerId()));
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
