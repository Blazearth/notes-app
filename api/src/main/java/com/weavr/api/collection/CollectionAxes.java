package com.weavr.api.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.weavr.api.common.KnowledgeFacets;

/**
 * How a collection subdivides — the axis chain, per knowledge type.
 *
 * <p>K1–K4 subdivided a type by exactly one <em>save-level</em> field
 * ({@code KnowledgeFacets.FACETS}: an itinerary's {@code destination}, a
 * recommendation list's {@code medium}). That is enough for one level of
 * folders and no more, and it reads the wrong thing for lists: a "top 10"
 * save's own {@code medium} describes the <em>list</em>, where the useful
 * split is what each recommended <em>item</em> actually is. This generalises
 * it in three directions at once:
 *
 * <ul>
 *   <li><b>A chain, not a field.</b> {@code recommendation_list} splits by
 *       item kind and then by genre, so Recommendations → Anime → Romance is
 *       a path rather than a special case.</li>
 *   <li><b>Entity-level as well as save-level.</b> A {@link Source#SAVE} axis
 *       reads the containing save's {@code structuredData} (every item in that
 *       save inherits the value); an {@link Source#ENTITY} axis reads the
 *       merged entity itself, so a title recommended by two saves is filed
 *       once, by what it <em>is</em>.</li>
 *   <li><b>Multi-valued.</b> {@code genre} and {@code muscleGroups} are
 *       arrays, so an entity legitimately belongs to several buckets at the
 *       same level. Counts stay honest because {@link CollectionNode#of}
 *       counts <em>distinct</em> entities across a subtree rather than summing
 *       children — the same rule that stopped one two-genre film reading as
 *       "2 items" in K1.</li>
 * </ul>
 *
 * <p>Kept deliberately as <em>data</em>: adding an axis to a type, or a new
 * item-bearing type, is an entry here plus an entry in
 * {@code CollectionService.ITEM_SHAPES} — never a change to the recursive
 * builder. Mirrored by hand in {@code app/src/collections/axes.ts}, the same
 * arrangement {@code CollectionService}/{@code merge.ts} already have; this
 * file is the source of truth if the two drift.
 */
public final class CollectionAxes {

    private CollectionAxes() {}

    /** Where an axis reads its value from. */
    public enum Source {
        /** The containing save's {@code structuredData} — every item in that save inherits the value. */
        SAVE,
        /** The merged entity: its resolved {@code kind}, or a rolled-up field. */
        ENTITY
    }

    /**
     * One level of subdivision.
     *
     * @param from         where to read the value
     * @param field        the field name, on the save's {@code structuredData} or on the merged entity
     * @param minGroupSize how many distinct entities must share a value before it earns its own node —
     *                     below this the entities stay at the parent level rather than sitting in a
     *                     folder of one. Per-axis rather than global because the right floor genuinely
     *                     differs: a destination naming a single trip is still the point of an
     *                     itinerary collection, where a genre holding one title is noise.
     * @param derive       maps <em>all</em> of one entity's raw values on this axis to the buckets it
     *                     belongs in — the escape hatch for a split the model never states
     *                     (Push/Pull/Legs from muscle groups). Identity for every axis that files by
     *                     what was extracted.
     *                     <p>Deliberately set-to-set rather than value-to-value: a derivation often
     *                     depends on the <em>combination</em>. A session training chest, back and legs
     *                     is a full-body session, not three separate ones, and a per-value mapping
     *                     cannot express that — it would file that session's squat under Push.
     * @param displayName  the bucket's product-facing name
     */
    public record CollectionAxis(
            Source from,
            String field,
            int minGroupSize,
            Function<List<String>, List<String>> derive,
            Function<String, String> displayName) {

        /** An axis that files by exactly what was extracted, named by title-casing it. */
        static CollectionAxis of(Source from, String field, int minGroupSize) {
            return new CollectionAxis(from, field, minGroupSize, values -> values, KnowledgeFacets::titleCase);
        }

        CollectionAxis named(Function<String, String> naming) {
            return new CollectionAxis(from, field, minGroupSize, derive, naming);
        }

        CollectionAxis deriving(Function<List<String>, List<String>> derivation) {
            return new CollectionAxis(from, field, minGroupSize, derivation, displayName);
        }
    }

    /**
     * The axis chain per item-bearing type, outermost first. A type absent
     * here still merges its items into entities — it simply presents them as
     * one flat list, which is exactly K1's behaviour and remains the honest
     * default for a type with no closed-ish vocabulary to split on.
     */
    public static final Map<String, List<CollectionAxis>> AXES = Map.of(
            // What a recommended thing *is*, then what it is *about*. Both read
            // from the merged entity, so a title recommended by an anime list
            // and a film list is filed once rather than once per source.
            "recommendation_list", List.of(
                    CollectionAxis.of(Source.ENTITY, "kind", 2).named(CollectionAxes::kindDisplayName),
                    CollectionAxis.of(Source.ENTITY, "genre", 2)),

            // The trip, not the stop. `destination` is save-level: every place
            // in "14 Days in Japan" is a Japan place. minGroupSize 1 because a
            // destination reached by a single save is still the thing the user
            // came here for — "Japan" is the knowledge object, and folding it
            // away would leave its places floating with nothing saying where
            // they are.
            "itinerary", List.of(CollectionAxis.of(Source.SAVE, "destination", 1)),

            "checklist", List.of(CollectionAxis.of(Source.SAVE, "category", 2)),

            // Push/Pull/Legs is not a field any save carries — `category` is
            // Strength/Cardio/Yoga, which does not separate two strength days.
            // It is derived from `muscleGroups` by a fixed table below: pure
            // local compute, cost class 2, no model call. minGroupSize 1 so a
            // single push day still presents as Push rather than as loose
            // exercises under Workouts.
            "workout", List.of(CollectionAxis.of(Source.SAVE, "muscleGroups", 1)
                    .deriving(CollectionAxes::trainingSplits)));

    public static List<CollectionAxis> axesFor(String knowledgeType) {
        return AXES.getOrDefault(knowledgeType, List.of());
    }

    /**
     * Product-facing names for the per-item {@code kind} vocabulary the
     * registry asks for ("film", "series", "sight"). A closed-ish map in
     * exactly the spirit of {@code KnowledgeFacets.DISPLAY_NAMES}: anything
     * absent falls back to title case, so a kind nobody anticipated still
     * gets a sensible folder instead of disappearing.
     */
    private static final Map<String, String> KIND_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("film", "Movies"),
            Map.entry("movie", "Movies"),
            Map.entry("tv", "Series"),
            Map.entry("series", "Series"),
            Map.entry("show", "Series"),
            Map.entry("anime", "Anime"),
            Map.entry("book", "Books"),
            Map.entry("game", "Games"),
            Map.entry("music", "Music"),
            Map.entry("podcast", "Podcasts"),
            Map.entry("place", "Places"),
            Map.entry("sight", "Sights"),
            Map.entry("restaurant", "Restaurants"),
            Map.entry("hotel", "Hotels"),
            Map.entry("area", "Areas"),
            Map.entry("product", "Products"),
            Map.entry("exercise", "Exercises"),
            Map.entry("task", "Tasks"));

    public static String kindDisplayName(String kind) {
        String normalised = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String mapped = KIND_DISPLAY_NAMES.get(normalised);
        return mapped != null ? mapped : KnowledgeFacets.titleCase(kind);
    }

    /**
     * Muscle group → training split. The vocabulary is the model's own
     * ({@code muscleGroups: ['chest', 'shoulders', 'triceps']}), and the
     * mapping is the standard one every push/pull/legs program uses.
     *
     * <p>Anything unrecognised falls through to its own title-cased bucket
     * rather than being dropped — a workout trained "grip" still files
     * somewhere, and the split names stay honest instead of forcing every
     * muscle into three folders that may not fit.
     */
    private static final Map<String, String> TRAINING_SPLITS = Map.ofEntries(
            Map.entry("chest", "Push"),
            Map.entry("pecs", "Push"),
            Map.entry("shoulders", "Push"),
            Map.entry("shoulder", "Push"),
            Map.entry("delts", "Push"),
            Map.entry("front delts", "Push"),
            Map.entry("triceps", "Push"),
            Map.entry("tricep", "Push"),
            Map.entry("back", "Pull"),
            Map.entry("lats", "Pull"),
            Map.entry("traps", "Pull"),
            Map.entry("rear delts", "Pull"),
            Map.entry("biceps", "Pull"),
            Map.entry("bicep", "Pull"),
            Map.entry("forearms", "Pull"),
            Map.entry("legs", "Legs"),
            Map.entry("quads", "Legs"),
            Map.entry("quadriceps", "Legs"),
            Map.entry("hamstrings", "Legs"),
            Map.entry("glutes", "Legs"),
            Map.entry("calves", "Legs"),
            Map.entry("adductors", "Legs"),
            Map.entry("core", "Core"),
            Map.entry("abs", "Core"),
            Map.entry("abdominals", "Core"),
            Map.entry("obliques", "Core"));

    /** How many distinct splits a session may span before it stops being any one of them. */
    private static final int FULL_BODY_THRESHOLD = 3;

    /** The name for a session that trains too much to belong to one split. */
    static final String FULL_BODY = "Full body";

    private static String splitOf(String muscleGroup) {
        String normalised = muscleGroup == null ? "" : muscleGroup.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) return null;
        String split = TRAINING_SPLITS.get(normalised);
        return split != null ? split : KnowledgeFacets.titleCase(muscleGroup);
    }

    /**
     * A session's muscle groups → the split(s) it belongs to.
     *
     * <p>A session spanning {@value #FULL_BODY_THRESHOLD} or more splits is
     * filed as one {@link #FULL_BODY} session rather than into each of them.
     * Without that rule, a full-body circuit's squat appears under Push
     * purely because the same circuit also pressed something — the split is a
     * property of the <em>session</em> (it is all the model gives us; there
     * is no per-exercise muscle group), so a session that is all of them is
     * none of them. {@code Core} does not count toward the span: nearly every
     * session trains it, and letting it push a push day over the line would
     * make "Full body" the only bucket anyone ever sees.
     */
    static List<String> trainingSplits(List<String> muscleGroups) {
        LinkedHashSet<String> splits = new LinkedHashSet<>();
        for (String muscleGroup : muscleGroups) {
            String split = splitOf(muscleGroup);
            if (split != null) splits.add(split);
        }
        long spanning = splits.stream().filter(split -> !"Core".equals(split)).count();
        return spanning >= FULL_BODY_THRESHOLD ? List.of(FULL_BODY) : new ArrayList<>(splits);
    }

    /** Applies an axis's derivation to one entity's raw values, de-duplicated and order-preserving. */
    static List<String> derivedValues(CollectionAxis axis, Collection<String> rawValues) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String derived : axis.derive().apply(List.copyOf(rawValues))) {
            if (derived != null && !derived.isBlank()) out.add(derived.trim());
        }
        return new ArrayList<>(out);
    }
}
