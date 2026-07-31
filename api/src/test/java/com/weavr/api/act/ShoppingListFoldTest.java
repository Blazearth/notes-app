package com.weavr.api.act;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic that decides what a shopping-list line says.
 *
 * <p><b>This exists because the obvious implementation was wrong and nothing
 * caught it but running the thing.</b> The first version accumulated: it added
 * each conversion's quantity to whatever was already on the line. That is
 * correct exactly once. The job runner re-delivers a job after any transient
 * failure or stale claim, and on the second delivery garlic went from 7 cloves
 * to 10 and olive oil from 4 tbsp to 6 — silently, with no error anywhere,
 * against the live database.
 *
 * <p>The fix is to store each contributing recipe's own quantity and recompute
 * the total, so the result depends only on <em>which</em> recipes are on the
 * list and not on how many times each was converted. These tests pin that
 * property directly.
 */
class ShoppingListFoldTest {

    private static final UUID PASTA = UUID.randomUUID();
    private static final UUID SHRIMP = UUID.randomUUID();

    private static ShoppingListService.Contribution from(UUID save, String quantity, String unit) {
        return new ShoppingListService.Contribution(save, quantity, unit);
    }

    @Test
    void sumsContributionsFromDifferentRecipes() {
        ShoppingListService.Total total = ShoppingListService.fold(List.of(
                from(PASTA, "3", "cloves"),
                from(SHRIMP, "4", "cloves")));

        assertThat(total.quantity()).isEqualTo("7");
        assertThat(total.unit()).isEqualTo("cloves");
    }

    /**
     * The regression this whole class is about: the same set of contributions
     * must produce the same total no matter how often the fold runs.
     */
    @Test
    void isIdempotentInTheContributionSet() {
        List<ShoppingListService.Contribution> contributions = List.of(
                from(PASTA, "3", "cloves"),
                from(SHRIMP, "4", "cloves"));

        assertThat(ShoppingListService.fold(contributions).quantity())
                .isEqualTo(ShoppingListService.fold(contributions).quantity())
                .isEqualTo("7");
    }

    /**
     * Re-converting a recipe replaces its contribution rather than adding a
     * second one — modelled here as the caller would build it.
     */
    @Test
    void replacingOneRecipesContributionDoesNotAccumulate() {
        List<ShoppingListService.Contribution> before = List.of(
                from(PASTA, "3", "cloves"), from(SHRIMP, "4", "cloves"));

        List<ShoppingListService.Contribution> afterReRun = List.of(
                from(SHRIMP, "4", "cloves"), from(PASTA, "3", "cloves"));

        assertThat(ShoppingListService.fold(afterReRun).quantity())
                .isEqualTo(ShoppingListService.fold(before).quantity());
    }

    @Test
    void droppingARecipeReducesTheTotalRatherThanLeavingItInflated() {
        assertThat(ShoppingListService.fold(List.of(from(SHRIMP, "4", "cloves"))).quantity())
                .isEqualTo("4");
    }

    @Test
    void aSingleContributionIsItsOwnTotal() {
        ShoppingListService.Total total =
                ShoppingListService.fold(List.of(from(PASTA, "400", "g")));

        assertThat(total.quantity()).isEqualTo("400");
        assertThat(total.unit()).isEqualTo("g");
    }

    /** Garnishes and "to taste" ingredients have no quantity at all. */
    @Test
    void handlesContributionsWithNoQuantity() {
        ShoppingListService.Total total = ShoppingListService.fold(List.of(
                from(PASTA, null, null), from(SHRIMP, null, null)));

        assertThat(total.quantity()).isNull();
    }

    @Test
    void keepsMismatchedUnitsVisibleInsteadOfConverting() {
        ShoppingListService.Total total = ShoppingListService.fold(List.of(
                from(PASTA, "200", "g"), from(SHRIMP, "1", "cup")));

        // Deliberately odd-looking: a silently converted number would be worse,
        // because it would look right.
        assertThat(total.quantity()).isEqualTo("200 + 1");
    }

    @Test
    void anEmptyContributionSetHasNoQuantity() {
        assertThat(ShoppingListService.fold(List.of()).quantity()).isNull();
    }
}
