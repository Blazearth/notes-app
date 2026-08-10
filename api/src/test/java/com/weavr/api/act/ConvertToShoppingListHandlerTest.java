package com.weavr.api.act;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
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

    // -------------------------------------------------------------- S4: who acted

    /**
     * Since S4 the converter's caller need not own the recipe — a Recipe
     * Space's members convert each other's saves into the shared list — so the
     * job carries the actor and the weekly Act cap is charged to them.
     */
    @Test
    void theActorIsReadFromTheJobPayload() {
        UUID actor = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        assertThat(ConvertToShoppingListHandler.actorOf(job(Map.of(
                "saveId", UUID.randomUUID().toString(),
                "actorId", actor.toString())), owner))
                .isEqualTo(actor);
    }

    /**
     * A job enqueued before S4 has no {@code actorId} at all, and there was
     * exactly one candidate then: the save's owner. A queue is not drained the
     * instant it is written to, so this fallback is what stops a deploy losing
     * every conversion already in flight.
     */
    @Test
    void aJobFromBeforeS4ChargesTheSavesOwner() {
        UUID owner = UUID.randomUUID();

        assertThat(ConvertToShoppingListHandler.actorOf(
                job(Map.of("saveId", UUID.randomUUID().toString())), owner))
                .isEqualTo(owner);
        // And a payload that carries something unusable falls back rather than
        // failing a job whose real work is perfectly well specified.
        assertThat(ConvertToShoppingListHandler.actorOf(
                job(Map.of("actorId", "not-a-uuid")), owner))
                .isEqualTo(owner);
    }

    private static JobRecord job(Map<String, Object> payload) {
        return new JobRecord(UUID.randomUUID(), JobType.CONVERT_TO_SHOPPING_LIST, payload, 0, 3, null);
    }
}
