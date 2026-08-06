package com.weavr.api.group;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.group.GroupService.SaveFacts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targets {@link GroupService#buildTree} and its two pure helpers, which are
 * deliberately functions of plain data — so every rule below is exercised with
 * no database, no Spring context, and no mocks that could drift out of step
 * with the real query.
 */
class GroupServiceTest {

    private static SaveFacts ready(String knowledgeType, Map<String, Object> structured) {
        return new SaveFacts(UUID.randomUUID(), knowledgeType, structured);
    }

    private static List<GroupNode> tree(SaveFacts... all) {
        // Use minGroupSize=1 so test fixtures with small datasets still produce subgroups.
        return GroupService.buildTree(List.of(all), 1);
    }

    @Test
    void groupsByKnowledgeTypeAndSubdividesByFacet() {
        List<GroupNode> groups = tree(
                ready("recipe", Map.of("title", "Carbonara", "cuisine", "Italian")),
                ready("recipe", Map.of("title", "Cacio e pepe", "cuisine", "Italian")),
                ready("recipe", Map.of("title", "Ramen", "cuisine", "Japanese")));

        assertThat(groups).hasSize(1);
        GroupNode recipes = groups.getFirst();
        assertThat(recipes.id()).isEqualTo("recipe");
        assertThat(recipes.name()).isEqualTo("Recipes");
        assertThat(recipes.subgroups()).extracting(GroupNode::name)
                .containsExactlyInAnyOrder("Italian", "Japanese");
        assertThat(recipes.subgroups()).extracting(GroupNode::id)
                .containsExactlyInAnyOrder("recipe~italian", "recipe~japanese");
    }

    /**
     * The count a folder shows must be its subtree, never what happens to sit
     * loose at its own level — a parent reading 1 while its children hold 3 is
     * exactly the detail that makes an interface untrustworthy.
     */
    @Test
    void parentCountCoversTheWholeSubtree() {
        GroupNode recipes = tree(
                ready("recipe", Map.of("cuisine", "Italian")),
                ready("recipe", Map.of("cuisine", "Japanese")),
                ready("recipe", Map.of("title", "No cuisine given"))).getFirst();

        assertThat(recipes.saveIds()).hasSize(1);   // only the one with no facet
        assertThat(recipes.itemCount()).isEqualTo(3);
    }

    /**
     * A save the model could not place stays at the type level. An invented
     * "Uncategorised" folder would name a folder after our extraction quality
     * rather than after the user's content.
     */
    @Test
    void savesWithNoFacetStayAtTheParentLevel() {
        GroupNode recipes = tree(ready("recipe", Map.of("title", "Something"))).getFirst();

        assertThat(recipes.subgroups()).isEmpty();
        assertThat(recipes.saveIds()).hasSize(1);
    }

    /**
     * {@code [unclear]} is the registry's sentinel for "the model could not
     * tell". Grouping by it produces a folder named after our own uncertainty —
     * the same reason migration V4 stripped it out of the search vector.
     */
    @Test
    void ignoresTheUnclearSentinelAsAFacet() {
        GroupNode recipes = tree(ready("recipe", Map.of("cuisine", "[unclear]"))).getFirst();

        assertThat(recipes.subgroups()).isEmpty();
        assertThat(recipes.saveIds()).hasSize(1);
    }

    /** {@code genre} arrives as an array, so a save can sit in two folders at once. */
    @Test
    void handlesArrayFacetsAndMultipleMembership() {
        GroupNode watchlist = tree(
                ready("movie", Map.of("title", "Parasite", "genre", List.of("Thriller", "Drama"))))
                .getFirst();

        assertThat(watchlist.name()).isEqualTo("Watchlist");
        assertThat(watchlist.subgroups()).extracting(GroupNode::name)
                .containsExactlyInAnyOrder("Thriller", "Drama");
    }

    /**
     * The regression this test exists for: summing children reported
     * "Watchlist · 2 items" for a library holding one film, because the film
     * was filed under two genres. A folder is asked how many things are in it,
     * not how many places we filed them.
     *
     * <p>Found by running the endpoint against a real database — the unit
     * tests up to that point all used single-valued facets and agreed with the
     * wrong answer.
     */
    @Test
    void countsDistinctSavesNotMemberships() {
        GroupNode watchlist = tree(
                ready("movie", Map.of("title", "Parasite", "genre", List.of("Thriller", "Drama"))))
                .getFirst();

        assertThat(watchlist.subgroups()).hasSize(2);
        assertThat(watchlist.itemCount()).isEqualTo(1);
    }

    /** {@code unusable} is the model reporting failure; it is not a category. */
    @Test
    void excludesUnusableSaves() {
        assertThat(tree(
                ready("unusable", Map.of("reason", "no text")),
                ready("recipe", Map.of("cuisine", "Italian"))))
                .extracting(GroupNode::id).containsExactly("recipe");
    }

    /**
     * {@code knowledge_type} is free text from the model, so a type this
     * service has never heard of must still get a folder rather than vanishing
     * from the library.
     */
    @Test
    void fallsBackToATitleCasedNameForAnUnknownType() {
        GroupNode group = tree(ready("podcast", Map.of("title", "Something"))).getFirst();

        assertThat(group.id()).isEqualTo("podcast");
        assertThat(group.name()).isEqualTo("Podcast");
    }

    @Test
    void findsANestedGroupById() {
        List<GroupNode> groups = tree(ready("recipe", Map.of("cuisine", "Italian")));

        assertThat(GroupService.find(groups, "recipe~italian")).isPresent();
        assertThat(GroupService.find(groups, "recipe~thai")).isEmpty();
    }

    @Test
    void deepCollectsTheWholeSubtreeAndShallowDoesNot() {
        GroupNode recipes = tree(
                ready("recipe", Map.of("cuisine", "Italian")),
                ready("recipe", Map.of("title", "Loose one"))).getFirst();

        assertThat(recipes.saveIds()).hasSize(1);
        assertThat(GroupService.collect(recipes)).hasSize(2);
    }

    /** Ids go in a URL path, so a facet with spaces and punctuation must survive it. */
    @Test
    void slugsAreUrlSafe() {
        GroupNode sub = tree(ready("recipe", Map.of("cuisine", "Modern British & Nordic")))
                .getFirst().subgroups().getFirst();

        assertThat(sub.id()).isEqualTo("recipe~modern-british-nordic");
    }

    /** An empty library is an empty tree, not a set of empty folders. */
    @Test
    void emptyLibraryProducesNoGroups() {
        assertThat(GroupService.buildTree(List.of())).isEmpty();
    }
}
