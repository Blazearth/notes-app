package com.weavr.api.enrich;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard against a search API confidently answering the wrong question.
 *
 * <p>These are the cases the threshold exists for: every one of them is a real
 * shape of input the pipeline produces — a caption title, an OCR title with a
 * lost accent, a place name that collides with an unrelated business.
 */
class TitleMatchTest {

    @Test
    void identicalTitlesMatch() {
        assertThat(TitleMatch.matches("Oppenheimer", "Oppenheimer")).isTrue();
    }

    /**
     * The asymmetry that matters: external sources know longer, more official
     * names than captions do. Penalising them for that would reject the correct
     * answer on half the catalogue.
     */
    @Test
    void aLongerOfficialTitleStillMatches() {
        assertThat(TitleMatch.matches("Dune", "Dune: Part Two")).isTrue();
        assertThat(TitleMatch.matches("Noma", "Noma — Restaurant & Test Kitchen")).isTrue();
    }

    /** The direction that should be punished: words we asked for that are simply absent. */
    @Test
    void extraWordsInTheQueryLowerTheScore() {
        assertThat(TitleMatch.similarity("The Bear Season Four Finale", "The Bear")).isLessThan(0.6);
    }

    /**
     * The whole reason this class exists. Google Places answers "Noma" with a
     * coffee shop rather than nothing, and without a guard its address would be
     * written onto a save about a Copenhagen restaurant.
     */
    @Test
    void anUnrelatedResultThatSharesAPrefixIsRejected() {
        assertThat(TitleMatch.matches("Noma", "Nomad Coffee Roasters")).isFalse();
        assertThat(TitleMatch.matches("Oppenheimer", "Openheimer Bakery")).isFalse();
    }

    /**
     * Accents survive a caption unevenly and rarely survive OCR at all, so the
     * comparison has to ignore them — otherwise the correct match is rejected
     * for a diacritic the user never typed.
     */
    @Test
    void accentsAreIgnored() {
        assertThat(TitleMatch.matches("Cafe de Flore", "Café de Flore")).isTrue();
        assertThat(TitleMatch.matches("Amelie", "Amélie")).isTrue();
    }

    @Test
    void punctuationAndCaseAreIgnored() {
        assertThat(TitleMatch.matches("wall-e", "WALL·E")).isTrue();
        assertThat(TitleMatch.matches("Se7en", "Se7en")).isTrue();
    }

    /** Garbage in — which is exactly what a low-confidence OCR title is — must not match anything. */
    @Test
    void garbledOcrTextDoesNotMatch() {
        assertThat(TitleMatch.matches("nigatoni sicloves", "Rigatoni")).isFalse();
    }

    @Test
    void nullAndBlankAreNeverAMatch() {
        assertThat(TitleMatch.matches(null, "Oppenheimer")).isFalse();
        assertThat(TitleMatch.matches("Oppenheimer", null)).isFalse();
        assertThat(TitleMatch.matches("   ", "Oppenheimer")).isFalse();
    }
}
