package com.weavr.api.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Entities#key} is the identity three romance-anime Reels merge on —
 * pure and database-free so the normalize spec can be pinned with no saves at
 * all. Several cases below use the two real titles K0 measured against the
 * dev database's actual {@code recommendation_list} saves rather than
 * invented examples.
 */
class EntitiesTest {

    @Test
    void collapsesCaseWhitespaceAndPunctuation() {
        assertThat(Entities.normalize("  Blue   Box  ")).isEqualTo("blue box");
        assertThat(Entities.normalize("BLUE BOX")).isEqualTo("blue box");
        assertThat(Entities.normalize("\"Blue Box\"")).isEqualTo("blue box");
    }

    /** A real title from the dev database's own recommendation_list saves (K0). */
    @Test
    void stripsALeadingArticle() {
        assertThat(Entities.normalize("The Second Prettiest Girl in My Class"))
                .isEqualTo("second prettiest girl in my class");
        assertThat(Entities.normalize("The Fragrant Flower Blooms With Dignity"))
                .isEqualTo("fragrant flower blooms with dignity");
        assertThat(Entities.normalize("A Silent Voice")).isEqualTo("silent voice");
        assertThat(Entities.normalize("An Ordinary Day")).isEqualTo("ordinary day");
    }

    /** "Article" stripping only fires on the leading word, not one that merely starts with "a". */
    @Test
    void doesNotStripAWordThatMerelyStartsWithAnArticle() {
        assertThat(Entities.normalize("Anime Club")).isEqualTo("anime club");
    }

    @Test
    void coarsensKindIntoANamespaceSoDifferentMediaDoNotCollide() {
        assertThat(Entities.key("anime", "Blue Box")).isEqualTo("screen:blue box");
        assertThat(Entities.key("film", "Blue Box")).isEqualTo("screen:blue box");
        assertThat(Entities.key("game", "Blue Box")).isEqualTo("game:blue box");
    }

    @Test
    void unknownKindFallsBackToItsOwnNamespaceRatherThanCollapsingIntoOther() {
        assertThat(Entities.key("sculpture", "David")).isEqualTo("sculpture:david");
    }

    @Test
    void nullOrBlankKindNamespacesAsOther() {
        assertThat(Entities.key(null, "Something")).isEqualTo("other:something");
        assertThat(Entities.key("  ", "Something")).isEqualTo("other:something");
    }

    /**
     * The seven real items across the dev database's two real
     * recommendation_list saves (K0): all distinct anime, zero overlap. Every
     * key must come out different — the measured baseline this spec was
     * frozen against produced no false merges.
     */
    @Test
    void theSevenRealDevDatabaseItemsAllProduceDistinctKeys() {
        String[] titles = {
                "Haimiya Senpai wa Kawaii", "Kuranika", "Gal's Can't Be Kind to Otaku",
                "Call of the Night", "Blue Box", "The Second Prettiest Girl in My Class",
                "The Fragrant Flower Blooms With Dignity"
        };
        long distinctKeys = java.util.Arrays.stream(titles)
                .map(title -> Entities.key("anime", title))
                .distinct()
                .count();
        assertThat(distinctKeys).isEqualTo(titles.length);
    }

    @Test
    void unicodeVariantsThatAreTheSameCharacterUnderNfkcCollide() {
        // "ﬁnal" (U+FB01 LATIN SMALL LIGATURE FI) NFKC-decomposes to "final".
        assertThat(Entities.normalize("ﬁnal fantasy")).isEqualTo("final fantasy");
    }
}
