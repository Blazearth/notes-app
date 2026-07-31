package com.weavr.api.act;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combining two recipes' worth of the same ingredient.
 *
 * <p>The failure mode here is quiet and expensive in the way only a shopping
 * list can be: a wrong number does not throw, it sends someone home with too
 * little garlic. So the rule is narrow on purpose — add only when both sides
 * are unambiguous and the units match, otherwise show both and let the reader
 * decide.
 */
class QuantitiesTest {

    private static String merge(String q1, String u1, String q2, String u2) {
        return Quantities.merge(q1, u1, q2, u2).orElse(null);
    }

    @Test
    void addsPlainNumbersWithMatchingUnits() {
        assertThat(merge("2", "cloves", "3", "cloves")).isEqualTo("5");
        assertThat(merge("400", "g", "100", "g")).isEqualTo("500");
    }

    /** Recipes are written in fractions far more often than decimals. */
    @Test
    void addsFractionsAndMixedNumbers() {
        assertThat(merge("1/2", "cup", "1/2", "cup")).isEqualTo("1");
        assertThat(merge("1 1/2", "cup", "1/2", "cup")).isEqualTo("2");
        assertThat(merge("0.5", "tsp", "1/4", "tsp")).isEqualTo("0.75");
    }

    /** "cup" and "cups" are the same unit; a shopper would never think otherwise. */
    @Test
    void treatsSingularAndPluralUnitsAsTheSame() {
        assertThat(merge("1", "cup", "2", "cups")).isEqualTo("3");
        assertThat(merge("1", "tbsp.", "1", "TBSP")).isEqualTo("2");
    }

    /**
     * The load-bearing refusal. Adding 200 g to 1 cup requires knowing what the
     * ingredient is and how dense it is — and a wrong answer here looks exactly
     * like a right one.
     */
    @Test
    void refusesToAddAcrossDifferentUnits() {
        assertThat(merge("200", "g", "1", "cup")).isEqualTo("200 + 1");
    }

    /** Prose quantities are what recipes actually contain, and they must survive. */
    @Test
    void keepsUnparseableQuantitiesSideBySide() {
        assertThat(merge("a pinch", null, "1/2", "tsp")).isEqualTo("a pinch + 1/2");
        assertThat(merge("2-3", "cloves", "1", "clove")).isEqualTo("2-3 + 1");
        assertThat(merge("to taste", null, "to taste", null)).isEqualTo("to taste");
    }

    /** A counted ingredient — "2 onions" — has no unit on either side. */
    @Test
    void addsCountedIngredientsThatHaveNoUnitAtAll() {
        assertThat(merge("2", null, "1", null)).isEqualTo("3");
        assertThat(merge("2", "", "1", null)).isEqualTo("3");
    }

    @Test
    void handlesAMissingQuantityOnEitherSide() {
        assertThat(merge(null, "cloves", "3", "cloves")).isEqualTo("3");
        assertThat(merge("3", "cloves", null, "cloves")).isEqualTo("3");
        assertThat(merge(null, null, null, null)).isNull();
        assertThat(merge("  ", null, "2", null)).isEqualTo("2");
    }

    /** Sums must read like quantities, not like floating point. */
    @Test
    void formatsSumsWithoutTrailingZeros() {
        assertThat(merge("1.50", "cup", "0.50", "cup")).isEqualTo("2");
        assertThat(merge("1/3", "cup", "1/3", "cup")).isEqualTo("0.6666");
    }

    @Test
    void doesNotDivideByZero() {
        assertThat(merge("1/0", "cup", "1", "cup")).isEqualTo("1/0 + 1");
    }

    @Test
    void namesAreComparedIgnoringCaseAndSpacing() {
        assertThat(ShoppingListService.normaliseName("  Heavy   Cream "))
                .isEqualTo(ShoppingListService.normaliseName("heavy cream"));
    }
}
