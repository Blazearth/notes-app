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

    @Test
    void onlyShapeOneTypesProduceACollection() {
        // movie is shape 2 (save-is-the-entity) and workout is shape 3 (synthesis) —
        // neither is wired in K1.
        assertThat(tree(
                ready("movie", Map.of("title", "Parasite")),
                ready("workout", Map.of("title", "Push day"))))
                .isEmpty();
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

    @Test
    void facetSubdividesEntitiesTheSameWayGroupsSubdivideSaves() {
        CollectionNode recs = tree(
                recommendationSave("anime", recommendationItem("Blue Box", Map.of("kind", "anime"))),
                recommendationSave("film", recommendationItem("Parasite", Map.of("kind", "film"))))
                .getFirst();

        assertThat(recs.subgroups()).extracting(CollectionNode::name)
                .containsExactlyInAnyOrder("Anime", "Film");
        assertThat(recs.subgroups()).extracting(CollectionNode::id)
                .containsExactlyInAnyOrder("recommendation_list~anime", "recommendation_list~film");
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
        assertThat(CollectionService.mergeType(saves, "recommendation_list", "film"))
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
}
