package com.weavr.api.collection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.collection.CollectionService.SaveFacts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targets {@link CollectionService#buildTree} and {@link CollectionService#mergeType},
 * both deliberately pure functions of plain data — mirrors
 * {@code GroupServiceTest}'s style: no database, no Spring context, no mocks
 * that could drift out of step with the real query.
 */
class CollectionServiceTest {

    private static int seq = 0;

    /** Monotonically increasing {@code createdAt} so "earliest save" tie-breaks are deterministic. */
    private static SaveFacts ready(String knowledgeType, Map<String, Object> structured) {
        return new SaveFacts(UUID.randomUUID(), knowledgeType, structured, Instant.EPOCH.plusSeconds(seq++));
    }

    private static List<CollectionNode> tree(SaveFacts... all) {
        // minGroupSize=1 so small test fixtures still produce facet subgroups.
        return CollectionService.buildTree(List.of(all), 1);
    }

    private static Map<String, Object> recommendationItem(String name, Map<String, Object> rest) {
        Map<String, Object> item = new LinkedHashMap<>(rest);
        item.put("name", name);
        return item;
    }

    private static SaveFacts recommendationSave(String medium, Map<String, Object>... items) {
        return ready("recommendation_list",
                Map.of("title", "A list", "medium", medium, "items", List.of((Object[]) items)));
    }

    /** An itinerary for one destination, whose places are named only (no per-place kind). */
    private static SaveFacts itinerarySave(String destination, String... placeNames) {
        List<Object> places = new java.util.ArrayList<>();
        for (String name : placeNames) places.add(Map.of("name", name, "kind", "sight"));
        return ready("itinerary", Map.of("title", destination + " trip", "destination", destination, "places", places));
    }

    /** A workout whose {@code muscleGroups} drive the derived training split. */
    private static SaveFacts workoutSave(List<String> muscleGroups, String... exerciseNames) {
        List<Object> exercises = new java.util.ArrayList<>();
        for (String name : exerciseNames) exercises.add(Map.of("name", name, "sets", "3", "reps", "8-12"));
        return ready("workout", Map.of("title", "A session", "muscleGroups", muscleGroups, "exercises", exercises));
    }

    /** Depth-first node ids under a root — the shape a drill-down actually navigates. */
    private static List<String> nodeIds(CollectionNode root) {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(root.id());
        root.subgroups().forEach(child -> ids.addAll(nodeIds(child)));
        return ids;
    }

    @Test
    void onlyItemBearingTypesProduceACollection() {
        // movie is shape 2 — a save-is-the-entity type only ever joins an
        // entity a shape-1 item already created, never stands alone. The
        // workout here carries no `exercises`, so it produces no entities
        // either, and a node with nothing in it is not built at all.
        assertThat(tree(
                ready("movie", Map.of("title", "Parasite")),
                ready("workout", Map.of("title", "Push day"))))
                .isEmpty();
    }

    /**
     * The counterpart to the above: {@code workout} <em>is</em> item-bearing,
     * so a save that actually carries a routine does produce a collection.
     * These two together pin the rule — the type is wired, the emptiness is
     * what suppressed it.
     */
    @Test
    void aWorkoutCarryingExercisesProducesACollection() {
        assertThat(tree(workoutSave(List.of("chest"), "Bench press")))
                .extracting(CollectionNode::name)
                .containsExactly("Workouts");
    }

    @Test
    void distinctItemsEachBecomeTheirOwnEntity() {
        // The real shape K0 measured against the dev database: two saves, seven
        // items total, zero overlap.
        CollectionNode recs = tree(
                recommendationSave("anime",
                        recommendationItem("Haimiya Senpai wa Kawaii", Map.of("kind", "anime")),
                        recommendationItem("Kuranika", Map.of("kind", "anime")),
                        recommendationItem("Gal's Can't Be Kind to Otaku", Map.of("kind", "anime"))),
                recommendationSave("anime",
                        recommendationItem("Call of the Night", Map.of("kind", "anime")),
                        recommendationItem("Blue Box", Map.of("kind", "anime")),
                        recommendationItem("The Second Prettiest Girl in My Class", Map.of("kind", "anime")),
                        recommendationItem("The Fragrant Flower Blooms With Dignity", Map.of("kind", "anime"))))
                .getFirst();

        assertThat(recs.entityCount()).isEqualTo(7);
        assertThat(recs.sourceCount()).isEqualTo(2);
    }

    /**
     * The constraint-3 trap, one level up from {@code GroupServiceTest
     * .countsDistinctSavesNotMemberships}: the same entity recommended in
     * three different saves is one entry, not three.
     */
    @Test
    void oneEntityInThreeSourcesCountsOnce() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))))
                .getFirst();

        assertThat(recs.entityCount()).isEqualTo(1);
        assertThat(recs.sourceCount()).isEqualTo(3);

        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                        recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                        recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime")))),
                "recommendation_list", null);
        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().sourceCount()).isEqualTo(3);
    }

    @Test
    void firstNonUnclearScalarFieldWinsAcrossSources() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime",
                                recommendationItem("Blue Box", Map.of("kind", "anime", "year", "[unclear]"))),
                        recommendationSave("anime",
                                recommendationItem("Blue Box", Map.of("kind", "anime", "year", "2021")))),
                "recommendation_list", null);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().fields()).containsEntry("year", "2021");
    }

    @Test
    void everySourceSaidUnclearMergesToUnclearRatherThanBeingInvented() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(recommendationSave("anime",
                        recommendationItem("Blue Box", Map.of("kind", "anime", "year", "[unclear]")))),
                "recommendation_list", null);

        assertThat(entities.getFirst().fields()).containsEntry("year", "[unclear]");
    }

    /** genre is a list field — the merge rule for it is a union, with no field-name-specific code. */
    @Test
    void listFieldsAreUnionedAcrossSources() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime",
                                recommendationItem("Blue Box", Map.of("kind", "anime", "genre", List.of("romance")))),
                        recommendationSave("anime",
                                recommendationItem("Blue Box",
                                        Map.of("kind", "anime", "genre", List.of("romance", "comedy"))))),
                "recommendation_list", null);

        assertThat(entities).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Object> genre = (List<Object>) entities.getFirst().fields().get("genre");
        assertThat(genre).containsExactlyInAnyOrder("romance", "comedy");
    }

    /**
     * A concatenated "best arc / underrated gem" paragraph would be neither
     * source's claim. Each source keeps its own reason; the rolled-up field is
     * a pick, not a blend.
     */
    @Test
    void reasonsAreNeverBlendedButEachSourceKeepsItsOwn() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime", recommendationItem("Blue Box",
                                Map.of("kind", "anime", "reason", "best enemies-to-lovers arc"))),
                        recommendationSave("anime", recommendationItem("Blue Box",
                                Map.of("kind", "anime", "reason", "underrated gem")))),
                "recommendation_list", null);

        CollectionEntity blueBox = entities.getFirst();
        assertThat(blueBox.fields().get("reason")).isIn("best enemies-to-lovers arc", "underrated gem");
        assertThat(blueBox.sources()).extracting(s -> s.item().get("reason"))
                .containsExactlyInAnyOrder("best enemies-to-lovers arc", "underrated gem");
    }

    /** Checklist items carry no kind field at all — every item namespaces as "task". */
    @Test
    void checklistItemsUseTheFixedTaskKind() {
        SaveFacts packingList = ready("checklist", Map.of(
                "title", "Packing", "category", "Travel",
                "items", List.of(Map.of("text", "Passport", "detail", "[unclear]", "optional", "no"))));

        List<CollectionEntity> entities = CollectionService.mergeType(List.of(packingList), "checklist", null);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().kind()).isEqualTo("task");
        assertThat(entities.getFirst().name()).isEqualTo("Passport");
    }

    /** No name means no identity to merge on — the item is skipped, the same way GroupService skips an unusable facet value. */
    @Test
    void itemsWithNoUsableNameAreSkipped() {
        SaveFacts save = recommendationSave("anime", recommendationItem("[unclear]", Map.of("kind", "anime")));

        assertThat(CollectionService.mergeType(List.of(save), "recommendation_list", null)).isEmpty();
    }

    @Test
    void nameResolutionPicksTheMostCommonSurfaceFormTiesToTheEarliestSave() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                        recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                        recommendationSave("anime", recommendationItem("BLUE BOX", Map.of("kind", "anime")))),
                "recommendation_list", null);

        assertThat(entities.getFirst().name()).isEqualTo("Blue Box");
    }

    /**
     * The first axis subdivides entities the way groups subdivide saves — but
     * by what each recommended item <em>is</em> ({@code kind}, read off the
     * merged entity) rather than by the list's own {@code medium}. A node's
     * <b>id keeps the extracted vocabulary</b> ({@code ~movie}) while its
     * <b>name is the product-facing one</b> ("Movies"), so renaming a folder
     * can never invalidate a deep link.
     */
    @Test
    void theFirstAxisSubdividesEntitiesByWhatEachItemIs() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("film", recommendationItem("Parasite", Map.of("kind", "movie"))))
                .getFirst();

        assertThat(recs.subgroups()).extracting(CollectionNode::name)
                .containsExactlyInAnyOrder("Anime", "Movies");
        assertThat(recs.subgroups()).extracting(CollectionNode::id)
                .containsExactlyInAnyOrder("recommendation_list~anime", "recommendation_list~movie");
    }

    /**
     * The bug this canonicalisation fixes: two items whose {@code kind} is
     * spelled differently ({@code "movie"} and {@code "film"}) but both
     * display as "Movies" must land in <em>one</em> bucket, not two siblings
     * that both happen to be named "Movies". Bucketing by the raw extracted
     * spelling — which is what {@link CollectionAxes#canonicalKind} replaced —
     * would produce exactly that duplicate.
     */
    @Test
    void kindSynonymsMergeIntoOneBucketNotDuplicateSiblings() {
        CollectionNode recs = tree(
                recommendationSave("mixed",
                        recommendationItem("Parasite", Map.of("kind", "movie")),
                        recommendationItem("Oldboy", Map.of("kind", "film"))))
                .getFirst();

        assertThat(recs.subgroups()).extracting(CollectionNode::name).containsExactly("Movies");
        assertThat(recs.subgroups()).extracting(CollectionNode::id).containsExactly("recommendation_list~movie");
        assertThat(recs.subgroups().getFirst().entityCount()).isEqualTo(2);
    }

    /**
     * An entity-level axis files by the <em>merged</em> entity, so a title
     * recommended by two lists that disagree about the list's medium is filed
     * once, under what the item itself is — the whole reason the axis moved
     * off the save-level {@code medium}.
     */
    @Test
    void anEntityLevelAxisFilesAMergedEntityOnceNotOncePerSource() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("mixed", recommendationItem("Blue Box", Map.of("kind", "anime"))))
                .getFirst();

        assertThat(recs.subgroups()).extracting(CollectionNode::name).containsExactly("Anime");
        assertThat(recs.entityCount()).isEqualTo(1);
        assertThat(recs.sourceCount()).isEqualTo(2);
    }

    /** The K1 exit criterion, verbatim: a collection's entityCount equals the length of its merged list. */
    @Test
    void entityCountEqualsTheMergedListsLength() {
        List<SaveFacts> saves = List.of(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Call of the Night", Map.of("kind", "anime"))));

        CollectionNode recs = CollectionService.buildTree(saves, 1).getFirst();
        List<CollectionEntity> merged = CollectionService.mergeType(saves, "recommendation_list", null);

        assertThat(recs.entityCount()).isEqualTo(merged.size());
    }

    @Test
    void mergeTypeFiltersByFacetValue() {
        List<SaveFacts> saves = List.of(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("film", recommendationItem("Parasite", Map.of("kind", "film"))));

        assertThat(CollectionService.mergeType(saves, "recommendation_list", "anime"))
                .extracting(CollectionEntity::name).containsExactly("Blue Box");
        // "film" canonicalises to "movie" (CollectionAxes.canonicalKind), so that's
        // the facet value the node actually resolves under.
        assertThat(CollectionService.mergeType(saves, "recommendation_list", "movie"))
                .extracting(CollectionEntity::name).containsExactly("Parasite");
    }

    @Test
    void mergeTypeOnAnUnwiredOrUnknownTypeReturnsEmptyRatherThanThrowing() {
        assertThat(CollectionService.mergeType(List.of(), "movie", null)).isEmpty();
        assertThat(CollectionService.mergeType(List.of(), "not_a_real_type", null)).isEmpty();
    }

    @Test
    void emptyLibraryProducesNoCollections() {
        assertThat(CollectionService.buildTree(List.of())).isEmpty();
    }

    /**
     * K2: the pure merge core has no database, so a freshly merged entity's
     * {@code state} is always {@code null} here — {@code CollectionService
     * .entities()} is the only place K2 state ever gets joined in, and that
     * needs a mocked {@code EntityStateService} to exercise, not a fixture.
     */
    @Test
    void mergedEntitiesCarryNoStateUntilCollectionServiceJoinsItIn() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime")))),
                "recommendation_list", null);

        assertThat(entities.getFirst().state()).isNull();
    }

    /**
     * K2: {@code doneCount} is 0 from the pure builder for the same reason
     * {@code state} is null above — {@code CollectionNode.withDoneCount} is
     * the second pass that fills it in once state is loaded.
     */
    @Test
    void doneCountIsZeroFromThePureBuilder() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))))
                .getFirst();

        assertThat(recs.doneCount()).isZero();
    }

    /** withDoneCount counts distinct entities across the whole subtree, the same rule entityCount already follows. */
    @Test
    void withDoneCountCountsDistinctEntitiesAcrossTheSubtree() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Call of the Night", Map.of("kind", "anime"))))
                .getFirst();

        CollectionNode withDone = recs.withDoneCount(Set.of(Entities.key("anime", "Blue Box")));

        assertThat(withDone.doneCount()).isEqualTo(1);
        assertThat(withDone.entityCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ K4a: canonical ids

    /**
     * The alias fix stated as a known limit of {@link Entities#key(String, String)}:
     * two different surface forms that both carry the same {@code tmdbId}
     * (written by {@code TmdbEnricher}/{@code RecommendationListEnricher} on a
     * confident match) collide even though no normalization rule over the
     * strings would ever unify "Shingeki no Kyojin" and "Attack on Titan".
     */
    @Test
    void itemsSharingATmdbIdMergeEvenWithDifferentSurfaceForms() {
        List<CollectionEntity> entities = CollectionService.mergeType(
                List.of(
                        recommendationSave("anime",
                                recommendationItem("Shingeki no Kyojin", Map.of("kind", "anime", "tmdbId", "1429"))),
                        recommendationSave("anime",
                                recommendationItem("Attack on Titan", Map.of("kind", "anime", "tmdbId", "1429")))),
                "recommendation_list", null);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().entityKey()).isEqualTo("tmdb:1429");
        assertThat(entities.getFirst().sourceCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ K4b: shape 2 join

    private static SaveFacts movieSave(Map<String, Object> data) {
        return ready("movie", data);
    }

    /**
     * "A movie save of Blue Box and a watchlist item named Blue Box resolve to
     * the same entity" (docs/knowledge-collections.md) — the review attaches
     * as an extra source, and its own fields (director, here) roll up into
     * the entity alongside the watchlist item's own {@code reason}.
     */
    @Test
    void shape2MovieSaveJoinsAnExistingWatchlistEntityAsAnAdditionalSource() {
        List<SaveFacts> ready = List.of(
                recommendationSave("anime",
                        recommendationItem("Blue Box", Map.of("kind", "anime", "reason", "best arc"))),
                movieSave(Map.of("title", "Blue Box", "director", "Someone", "synopsis", "A romance anime")));

        List<CollectionEntity> entities = CollectionService.mergeType(ready, "recommendation_list", null);

        assertThat(entities).hasSize(1);
        CollectionEntity blueBox = entities.getFirst();
        assertThat(blueBox.sourceCount()).isEqualTo(2);
        assertThat(blueBox.fields()).containsEntry("director", "Someone");
        assertThat(blueBox.fields()).containsEntry("reason", "best arc");
    }

    /** No shape-1 item shares its key — the movie save stays an ordinary, individually-presented save, unchanged. */
    @Test
    void shape2SaveWithNoMatchingEntityContributesNothing() {
        List<SaveFacts> ready = List.of(
                recommendationSave("anime", recommendationItem("Call of the Night", Map.of("kind", "anime"))),
                movieSave(Map.of("title", "Parasite", "director", "Bong Joon-ho")));

        List<CollectionEntity> entities = CollectionService.mergeType(ready, "recommendation_list", null);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().sourceCount()).isEqualTo(1);
    }

    /** Shape 2 types never get their own top-level node — K1's scoping holds even once shape-2 data exists. */
    @Test
    void shape2TypesStillProduceNoTopLevelNodeOfTheirOwn() {
        List<SaveFacts> ready = List.of(movieSave(Map.of("title", "Parasite")));

        assertThat(CollectionService.buildTree(ready, 1)).isEmpty();
        assertThat(CollectionService.mergeType(ready, "movie", null)).isEmpty();
    }

    // ------------------------------------------------------------------ K4c: manual overrides

    // ------------------------------------------------------------------
    // The four structural paths this redesign has to be able to represent.
    // Each one is a real navigation path in the Library, asserted end to end:
    // the tree contains the node, and the node resolves to the right entities.
    // ------------------------------------------------------------------

    /** Itineraries → Japan → Tokyo, and Itineraries → Switzerland → Zurich. */
    @Test
    void itinerariesSplitByDestinationAndHoldTheirPlaces() {
        List<SaveFacts> saves = List.of(
                itinerarySave("Japan", "Tokyo", "Kyoto"),
                itinerarySave("Switzerland", "Zurich", "Interlaken"));

        CollectionNode itineraries = CollectionService.buildTree(saves).getFirst();

        assertThat(itineraries.name()).isEqualTo("Itineraries");
        assertThat(itineraries.subgroups()).extracting(CollectionNode::name)
                .containsExactlyInAnyOrder("Japan", "Switzerland");
        assertThat(itineraries.entityCount()).isEqualTo(4);

        assertThat(CollectionService.mergeNode(saves, "itinerary~japan", CollectionOverrides.EMPTY))
                .extracting(CollectionEntity::name).containsExactly("Tokyo", "Kyoto");
        assertThat(CollectionService.mergeNode(saves, "itinerary~switzerland", CollectionOverrides.EMPTY))
                .extracting(CollectionEntity::name).containsExactly("Zurich", "Interlaken");
    }

    /** Recommendations → Anime → Romance → Call of the Night: two axes deep. */
    @Test
    void recommendationsSplitByKindThenGenre() {
        List<SaveFacts> saves = List.of(
                recommendationSave("anime",
                        recommendationItem("Call of the Night", Map.of("kind", "anime", "genre", List.of("romance"))),
                        recommendationItem("Blue Box", Map.of("kind", "anime", "genre", List.of("romance", "sports"))),
                        recommendationItem("Vinland Saga", Map.of("kind", "anime", "genre", List.of("action")))),
                recommendationSave("film",
                        recommendationItem("Parasite", Map.of("kind", "film", "genre", List.of("thriller"))),
                        recommendationItem("Oldboy", Map.of("kind", "film", "genre", List.of("thriller")))));

        CollectionNode recs = CollectionService.buildTree(saves).getFirst();

        assertThat(nodeIds(recs)).contains(
                "recommendation_list",
                "recommendation_list~anime",
                "recommendation_list~anime~romance",
                "recommendation_list~movie~thriller");

        assertThat(CollectionService.mergeNode(saves, "recommendation_list~anime~romance", CollectionOverrides.EMPTY))
                .extracting(CollectionEntity::name)
                .containsExactly("Call of the Night", "Blue Box");
    }

    /** Workouts → Push → Bench Press, where "Push" is derived from muscle groups, not extracted. */
    @Test
    void workoutsSplitByDerivedTrainingSplit() {
        List<SaveFacts> saves = List.of(
                workoutSave(List.of("chest", "triceps"), "Bench press", "Overhead press"),
                workoutSave(List.of("back", "biceps"), "Barbell row", "Chin-up"));

        CollectionNode workouts = CollectionService.buildTree(saves).getFirst();

        assertThat(workouts.subgroups()).extracting(CollectionNode::name)
                .containsExactlyInAnyOrder("Push", "Pull");
        assertThat(CollectionService.mergeNode(saves, "workout~push", CollectionOverrides.EMPTY))
                .extracting(CollectionEntity::name).containsExactly("Bench press", "Overhead press");
    }

    /**
     * A session spanning three or more splits is one Full body session, not
     * three. The failure this prevents is concrete and was found by running
     * the real fixtures: a full-body circuit's squat appeared under Push,
     * purely because the same circuit also pressed something. The split is a
     * property of the session — the model gives no per-exercise muscle group
     * — so a session that is all of them is none of them.
     */
    @Test
    void aSessionSpanningThreeSplitsIsFullBodyRatherThanAllOfThem() {
        List<SaveFacts> saves = List.of(
                workoutSave(List.of("legs", "back", "chest", "core"), "Banded squat", "Banded row"));

        CollectionNode workouts = CollectionService.buildTree(saves).getFirst();

        assertThat(workouts.subgroups()).extracting(CollectionNode::name).containsExactly("Full body");
        assertThat(CollectionService.mergeNode(saves, "workout~push", CollectionOverrides.EMPTY)).isEmpty();
    }

    /** Core rides along on nearly every session, so it must not push a push day over the line. */
    @Test
    void coreDoesNotCountTowardTheFullBodySpan() {
        List<SaveFacts> saves = List.of(workoutSave(List.of("chest", "triceps", "abs"), "Bench press"));

        assertThat(CollectionService.buildTree(saves).getFirst().subgroups())
                .extracting(CollectionNode::name)
                .containsExactly("Push", "Core");
    }

    /** An unrecognised muscle group keeps its own bucket rather than being dropped. */
    @Test
    void anUnmappedMuscleGroupStillFilesSomewhere() {
        List<SaveFacts> saves = List.of(workoutSave(List.of("grip"), "Farmer's carry"));

        assertThat(CollectionService.buildTree(saves).getFirst().subgroups())
                .extracting(CollectionNode::name)
                .containsExactly("Grip");
    }

    /**
     * The claim the whole feature rests on: the same thing named by two saves
     * is <em>one</em> entity carrying both sources, not two rows — and the
     * counts above it say 3, not 4.
     */
    @Test
    void oneEntityNamedByTwoSavesIsMergedNotDuplicated() {
        List<SaveFacts> saves = List.of(
                itinerarySave("Japan", "Tokyo", "Kyoto"),
                itinerarySave("Japan", "Tokyo", "Osaka"));

        CollectionNode japan = CollectionService.buildTree(saves).getFirst().subgroups().getFirst();
        assertThat(japan.name()).isEqualTo("Japan");
        assertThat(japan.entityCount()).isEqualTo(3);
        assertThat(japan.sourceCount()).isEqualTo(2);

        List<CollectionEntity> entities =
                CollectionService.mergeNode(saves, "itinerary~japan", CollectionOverrides.EMPTY);
        assertThat(entities).extracting(CollectionEntity::name).containsExactly("Tokyo", "Kyoto", "Osaka");
        assertThat(entities.getFirst().sourceCount()).isEqualTo(2);
    }

    /**
     * A multi-valued axis puts one entity in several buckets at the same
     * level. The parent must still count it once — the K1 distinct-not-summed
     * rule, now load-bearing at every depth rather than only the first.
     */
    @Test
    void aMultiValuedAxisCountsAnEntityOnceAcrossTheBucketsItSitsIn() {
        List<SaveFacts> saves = List.of(recommendationSave("anime",
                recommendationItem("Blue Box", Map.of("kind", "anime", "genre", List.of("romance", "sports"))),
                recommendationItem("Haikyuu", Map.of("kind", "anime", "genre", List.of("sports"))),
                recommendationItem("Horimiya", Map.of("kind", "anime", "genre", List.of("romance")))));

        CollectionNode anime = CollectionService.buildTree(saves).getFirst().subgroups().getFirst();

        assertThat(anime.subgroups()).extracting(CollectionNode::name)
                .containsExactlyInAnyOrder("Romance", "Sports");
        assertThat(anime.subgroups()).extracting(CollectionNode::entityCount).containsExactly(2, 2);
        // 2 + 2 = 4 memberships over 3 distinct titles.
        assertThat(anime.entityCount()).isEqualTo(3);
    }

    /**
     * An entity whose only bucket at this level was too small stays at the
     * parent level; one that also belongs to a surviving bucket must not be
     * listed twice.
     */
    @Test
    void anEntityFallsThroughToLooseOnlyWhenNoBucketItLandedInSurvived() {
        List<SaveFacts> saves = List.of(recommendationSave("anime",
                recommendationItem("Blue Box", Map.of("kind", "anime", "genre", List.of("romance", "isekai"))),
                recommendationItem("Horimiya", Map.of("kind", "anime", "genre", List.of("romance"))),
                recommendationItem("Frieren", Map.of("kind", "anime", "genre", List.of("fantasy")))));

        CollectionNode anime = CollectionService.buildTree(saves).getFirst().subgroups().getFirst();

        // romance has 2 and survives; isekai and fantasy have 1 each and do not.
        assertThat(anime.subgroups()).extracting(CollectionNode::name).containsExactly("Romance");
        // Frieren's only genre was too small, so it stays here. Blue Box does
        // not, even though its `isekai` bucket was also too small.
        assertThat(anime.entityKeys()).hasSize(1);
        assertThat(anime.entityCount()).isEqualTo(3);
    }

    /**
     * A node id resolves to its entities regardless of whether the subgroup
     * was large enough to be worth <em>showing</em> — the two are separate
     * questions, and tying them together made a well-formed id answer empty.
     */
    @Test
    void nodeResolutionIsIndependentOfTheDisplayThreshold() {
        List<SaveFacts> saves = List.of(recommendationSave("anime",
                recommendationItem("Frieren", Map.of("kind", "anime", "genre", List.of("fantasy")))));

        // One entity clears no threshold at either level, so the tree is a
        // bare type node with the entity held directly on it...
        CollectionNode recs = CollectionService.buildTree(saves).getFirst();
        assertThat(recs.subgroups()).isEmpty();
        assertThat(recs.entityCount()).isEqualTo(1);

        // ...and yet the full path still answers with it.
        assertThat(CollectionService.mergeNode(saves, "recommendation_list~anime~fantasy", CollectionOverrides.EMPTY))
                .extracting(CollectionEntity::name).containsExactly("Frieren");
    }

    @Test
    void aPathDeeperThanTheTypesAxisChainResolvesToNothing() {
        List<SaveFacts> saves = List.of(itinerarySave("Japan", "Tokyo"));
        assertThat(CollectionService.mergeNode(saves, "itinerary~japan~tokyo~more", CollectionOverrides.EMPTY))
                .isEmpty();
    }

    private static CollectionOverrides mergeOverride(String fromKey, String intoKey) {
        return new CollectionOverrides(Map.of(fromKey, intoKey), Map.of(), Map.of());
    }

    /**
     * The user's escape hatch for the alias problem when no canonical id
     * exists to resolve it automatically — same outcome as the tmdbId test
     * above, applied by hand instead of by enrichment.
     */
    @Test
    void manualMergeOverrideCombinesTwoEntitiesIntoOne() {
        String from = Entities.key("anime", "Shingeki no Kyojin");
        String into = Entities.key("anime", "Attack on Titan");
        List<SaveFacts> ready = List.of(
                recommendationSave("anime", recommendationItem("Shingeki no Kyojin", Map.of("kind", "anime"))),
                recommendationSave("anime", recommendationItem("Attack on Titan", Map.of("kind", "anime"))));

        List<CollectionEntity> entities = CollectionService.mergeType(
                ready, "recommendation_list", null, mergeOverride(from, into));

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().entityKey()).isEqualTo(into);
        assertThat(entities.getFirst().sourceCount()).isEqualTo(2);
    }

    /** A merge redirect must also apply to shape-2 saves computing the same losing key. */
    @Test
    void manualMergeOverrideAlsoRedirectsAShape2Occurrence() {
        String from = Entities.key("movie", "Bluebox");
        String into = Entities.key("anime", "Blue Box");
        List<SaveFacts> ready = List.of(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                movieSave(Map.of("title", "Bluebox", "director", "Someone")));

        List<CollectionEntity> entities = CollectionService.mergeType(
                ready, "recommendation_list", null, mergeOverride(from, into));

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().sourceCount()).isEqualTo(2);
        assertThat(entities.getFirst().fields()).containsEntry("director", "Someone");
    }

    @Test
    void entityRenameOverrideReplacesTheResolvedSurfaceForm() {
        String key = Entities.key("anime", "Blue Box");
        CollectionOverrides overrides = new CollectionOverrides(Map.of(), Map.of(key, "My Favourite Anime"), Map.of());
        List<SaveFacts> ready = List.of(recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))));

        List<CollectionEntity> entities = CollectionService.mergeType(ready, "recommendation_list", null, overrides);

        assertThat(entities.getFirst().name()).isEqualTo("My Favourite Anime");
    }

    @Test
    void collectionRenameOverrideReplacesTheNodeName() {
        CollectionOverrides overrides =
                new CollectionOverrides(Map.of(), Map.of(), Map.of("recommendation_list", "My Watchlist"));
        List<SaveFacts> ready = List.of(recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))));

        CollectionNode recs = CollectionService.buildTree(ready, 1, overrides).getFirst();

        assertThat(recs.name()).isEqualTo("My Watchlist");
    }

    @Test
    void itineraryPlacesMergeTheSameWayRecommendationsDo() {
        SaveFacts trip = ready("itinerary", Map.of(
                "title", "3 Days in Kyoto", "destination", "Kyoto",
                "places", List.of(
                        Map.of("name", "Fushimi Inari", "kind", "sight", "area", "[unclear]"),
                        Map.of("name", "Fushimi Inari", "kind", "sight", "area", "Fushimi"))));

        List<CollectionEntity> entities = CollectionService.mergeType(List.of(trip), "itinerary", null);

        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().fields()).containsEntry("area", "Fushimi");
        assertThat(entities.getFirst().sources()).hasSize(1); // same save mentioning it twice still counts once
    }

    // ------------------------------------------------------------------ S1: an arbitrary scope
    //
    // docs/knowledge-spaces.md's S1: the merge takes the save set to merge
    // over instead of assuming "the caller's ready saves", and attributes each
    // source to whoever saved it.

    /** A save owned by someone in particular — the only thing the Space-scoped load adds. */
    private static SaveFacts savedBy(UUID ownerId, String knowledgeType, Map<String, Object> structured) {
        return new SaveFacts(UUID.randomUUID(), knowledgeType, structured, Instant.EPOCH.plusSeconds(seq++), ownerId);
    }

    private static SaveFacts recommendationSavedBy(UUID ownerId, String medium, Map<String, Object> item) {
        return savedBy(ownerId, "recommendation_list",
                Map.of("title", "A list", "medium", medium, "items", List.of(item)));
    }

    /**
     * The doc's own pinned regression, verbatim: "two members saving Reels that
     * share an entity produce one entity with two sources and distinct
     * {@code addedBy}". This is the whole of what S1 adds to the merge — the
     * "saved by Maya" attribution the client cannot derive, because a save's
     * owner is not on {@code SaveResponse}.
     */
    @Test
    void twoMembersSharingAnEntityProduceOneEntityWithTwoDistinctlyAttributedSources() {
        UUID aryan = UUID.randomUUID();
        UUID maya = UUID.randomUUID();
        List<SaveFacts> spaceSaves = List.of(
                recommendationSavedBy(aryan, "anime",
                        recommendationItem("Blue Box", Map.of("kind", "anime", "reason", "best arc"))),
                recommendationSavedBy(maya, "anime",
                        recommendationItem("Blue Box", Map.of("kind", "anime", "reason", "underrated"))));

        List<CollectionEntity> entities =
                CollectionService.entitiesOf(spaceSaves, "recommendation_list", null);

        assertThat(entities).hasSize(1);
        CollectionEntity blueBox = entities.getFirst();
        assertThat(blueBox.sourceCount()).isEqualTo(2);
        assertThat(blueBox.sources()).extracting(CollectionEntity.Source::addedBy)
                .containsExactlyInAnyOrder(aryan, maya);
        // Each member's own reason stays theirs — K1's "never blend, always
        // attribute" rule is exactly what makes the Space view worth having.
        assertThat(blueBox.sources()).extracting(source -> source.item().get("reason"))
                .containsExactlyInAnyOrder("best arc", "underrated");
    }

    /** A personal read names nobody: {@code addedBy} would be the caller on every row. */
    @Test
    void aPersonalMergeLeavesAddedByNull() {
        List<SaveFacts> ready = List.of(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))));

        CollectionEntity entity = CollectionService.mergeType(ready, "recommendation_list", null).getFirst();

        assertThat(entity.sources()).allSatisfy(source -> assertThat(source.addedBy()).isNull());
    }

    /**
     * {@code treeOf} is the same tree the personal path builds — the scope
     * changed, the shape did not. Asserted against the type-level counts,
     * because a Space's Overview reads exactly those.
     */
    @Test
    void treeOfBuildsTheSameShapeOverASpacesSaves() {
        UUID aryan = UUID.randomUUID();
        UUID maya = UUID.randomUUID();
        List<SaveFacts> spaceSaves = List.of(
                recommendationSavedBy(aryan, "anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSavedBy(maya, "anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSavedBy(maya, "anime", recommendationItem("Frieren", Map.of("kind", "anime"))));

        List<CollectionNode> nodes = CollectionService.treeOf(spaceSaves);

        assertThat(nodes).hasSize(1);
        // Two distinct titles over three saves — the merge-not-sum rule, which
        // is the number the Space Overview shows as "2 titles · 3 sources".
        assertThat(nodes.getFirst().entityCount()).isEqualTo(2);
        assertThat(nodes.getFirst().sourceCount()).isEqualTo(3);
    }

    /**
     * A Space-scoped merge applies no {@link CollectionOverrides}. They are one
     * <em>user's</em> curation, and reshaping a shared view by one member's
     * private renames would show the group something only that member asked
     * for. Pinned because the tempting "just pass the caller's" is a one-line
     * change that nothing else would catch.
     */
    @Test
    void aSpaceScopedMergeIgnoresAnyOneMembersOverrides() {
        UUID aryan = UUID.randomUUID();
        List<SaveFacts> spaceSaves = List.of(
                recommendationSavedBy(aryan, "anime", recommendationItem("Blue Box", Map.of("kind", "anime"))));

        assertThat(CollectionService.entitiesOf(spaceSaves, "recommendation_list", null).getFirst().name())
                .isEqualTo("Blue Box");
        assertThat(CollectionService.treeOf(spaceSaves).getFirst().name()).isEqualTo("Recommendations");
    }
}
