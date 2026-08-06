package com.weavr.api.act;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingredients reach the Act in two permanent shapes: flat strings from saves
 * classified before 2026-08-07, and {@code {name, quantity, note}} objects
 * after. There is no reprocess path, so neither shape ever ages out — a
 * regression in either silently produces an empty shopping list, which the
 * user reads as the button doing nothing.
 */
class ConvertToShoppingListHandlerTest {

    @Test
    void legacyStringIngredientsPassThroughUnchanged() {
        List<String> lines = ConvertToShoppingListHandler.ingredientLines(
                List.of("3 cloves garlic, minced", "250g mascarpone"));

        assertThat(lines).containsExactly("3 cloves garlic, minced", "250g mascarpone");
    }

    @Test
    void structuredIngredientsFoldBackIntoTheLineTheConverterPromptExpects() {
        List<String> lines = ConvertToShoppingListHandler.ingredientLines(List.of(
                Map.of("name", "garlic", "quantity", "3 cloves", "note", "minced"),
                Map.of("name", "mascarpone", "quantity", "250g", "note", "[unclear]"),
                Map.of("name", "coffee", "quantity", "[unclear]", "note", "[unclear]")));

        assertThat(lines).containsExactly("3 cloves garlic, minced", "250g mascarpone", "coffee");
    }

    @Test
    void dropsTheUnclearSentinelAndNamelessObjectsRatherThanShoppingForThem() {
        List<String> lines = ConvertToShoppingListHandler.ingredientLines(List.of(
                "[unclear]",
                "  ",
                Map.of("name", "[unclear]", "quantity", "2 tbsp", "note", "[unclear]"),
                "1 cup heavy cream"));

        assertThat(lines).containsExactly("1 cup heavy cream");
    }

    @Test
    void bothShapesCanCoexistInOneList() {
        List<String> lines = ConvertToShoppingListHandler.ingredientLines(List.of(
                "2 tbsp olive oil",
                Map.of("name", "rigatoni", "quantity", "400g", "note", "[unclear]")));

        assertThat(lines).containsExactly("2 tbsp olive oil", "400g rigatoni");
    }

    @Test
    void nonListInputYieldsNoLines() {
        assertThat(ConvertToShoppingListHandler.ingredientLines(null)).isEmpty();
        assertThat(ConvertToShoppingListHandler.ingredientLines("not a list")).isEmpty();
    }
}
