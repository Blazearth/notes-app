package com.weavr.api.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns a recipe's ingredient lines into shoppable items.
 *
 * <p><b>Why this needs a model at all.</b> A recipe says "3 cloves garlic,
 * minced" and "1 cup heavy cream, divided". A shopping list wants
 * <em>garlic</em> in produce and <em>heavy cream</em> in dairy, with the prep
 * instructions dropped and the quantity kept. Splitting that reliably with
 * string rules means encoding every unit, every prep verb and every "or to
 * taste" — and it breaks on the first recipe written slightly differently. This
 * is exactly the shape of problem the model is cheap at.
 *
 * <p><b>The aisle vocabulary is a closed set on purpose.</b> Free-form
 * categories would produce "Produce", "produce", "Fruit & Veg" and
 * "Vegetables" across four recipes, and the grouping — the entire point of the
 * feature — would fall apart. The enum lives in the response schema so the
 * model cannot return anything else.
 */
@Component
public class ShoppingListConverter {

    /**
     * Supermarket sections, in the order most shops are laid out. The order
     * matters: it is what the list is sorted by, so a shopper walks the aisles
     * once rather than criss-crossing.
     */
    static final List<String> CATEGORIES = List.of(
            "produce", "bakery", "meat and fish", "dairy and eggs",
            "pantry", "frozen", "drinks", "household", "other");

    private static final Set<String> KNOWN = Set.copyOf(CATEGORIES);

    private static final String SYSTEM_PROMPT = """
            You convert recipe ingredient lines into a supermarket shopping list.

            For each ingredient, return the thing you would actually buy:

            - name: the product, with preparation removed. "3 cloves garlic, \
              minced" is "garlic". "1 cup heavy cream, divided" is "heavy cream". \
              Do not include quantities in the name.
            - quantity: the amount, as written. Keep "a pinch", "2-3" and \
              "to taste" exactly as they appear — do not invent a number.
            - unit: the unit only ("g", "cup", "tbsp", "clove"), or omit it when \
              the ingredient is counted ("2 onions" is quantity 2, no unit).
            - category: the supermarket section, from the allowed list only.

            Rules:
            - Combine duplicate lines of the same ingredient into one item.
            - Split ingredients that name more than one product. "salt and \
              pepper to taste" is TWO items, salt and pepper — they are \
              separate things on a shelf, and a list that merges them cannot \
              merge either with the same ingredient from another recipe.
            - Skip water, and skip anything that is purely an instruction.
            - Salt and pepper belong in pantry.
            - If an ingredient is genuinely unclear, still include it with the \
              text you have rather than dropping it — an extra line on a list \
              costs nothing, a missing one costs a second trip.
            """;

    private final GeminiClient gemini;

    ShoppingListConverter(GeminiClient gemini) {
        this.gemini = gemini;
    }

    /** One line destined for the list, before it is merged into an existing one. */
    public record ItemDraft(String name, String quantity, String unit, String category) {
    }

    /**
     * @param saveId      the recipe, for {@code gemini_calls} attribution
     * @param title       gives the model context for ambiguous ingredients
     * @param ingredients the recipe's raw ingredient lines
     * @param budget      proof the daily budget was checked — an Act spends a
     *                    request like any other generation call
     */
    public List<ItemDraft> convert(UUID saveId, String title, List<String> ingredients,
                                   BudgetApproved budget) {
        if (ingredients.isEmpty()) {
            return List.of();
        }

        String userText = "Recipe: " + (title == null ? "untitled" : title) + "\nIngredients:\n"
                + String.join("\n", ingredients);

        JsonNode json = gemini.generateJson(
                saveId, SYSTEM_PROMPT, userText, responseSchema(), "shopping_list", budget);

        JsonNode items = json.path("items");
        if (!items.isArray()) {
            return List.of();
        }

        List<ItemDraft> drafts = new ArrayList<>();
        for (JsonNode item : items) {
            String name = text(item, "name");
            if (name == null) {
                continue;
            }
            drafts.add(new ItemDraft(
                    name,
                    text(item, "quantity"),
                    text(item, "unit"),
                    // Defensive despite the schema enum: a category outside the
                    // vocabulary would create an aisle the UI cannot order,
                    // which is a silently broken list rather than an error.
                    category(text(item, "category"))));
        }
        return drafts;
    }

    static Map<String, Object> responseSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "name", Map.of("type", "string",
                                                        "description", "The product to buy, no quantity, no preparation"),
                                                "quantity", Map.of("type", "string",
                                                        "description", "Amount as written, or empty"),
                                                "unit", Map.of("type", "string",
                                                        "description", "Unit only, or empty when counted"),
                                                "category", Map.of("type", "string",
                                                        "enum", CATEGORIES)),
                                        "required", List.of("name", "category")))),
                "required", List.of("items"));
    }

    private static String category(String value) {
        if (value == null) return "other";
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(normalised) ? normalised : "other";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) return null;
        String trimmed = value.asString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
