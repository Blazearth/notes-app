package com.weavr.api.act;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingListConverterTest {

    private static final UUID SAVE_ID = UUID.randomUUID();

    private final GeminiClient gemini = mock(GeminiClient.class);
    private final ShoppingListConverter converter = new ShoppingListConverter(gemini);

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    private void respondWith(String body) {
        when(gemini.generateJson(any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(json(body));
    }

    private List<ShoppingListConverter.ItemDraft> convert() {
        return converter.convert(SAVE_ID, "Creamy Tomato Pasta",
                List.of("400g rigatoni", "3 cloves garlic, minced"),
                mock(BudgetApproved.class));
    }

    @Test
    void parsesItemsIntoDrafts() {
        respondWith("""
                {"items":[
                  {"name":"rigatoni","quantity":"400","unit":"g","category":"pantry"},
                  {"name":"garlic","quantity":"3","unit":"cloves","category":"produce"}
                ]}""");

        assertThat(convert())
                .extracting(ShoppingListConverter.ItemDraft::name,
                        ShoppingListConverter.ItemDraft::quantity,
                        ShoppingListConverter.ItemDraft::category)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("rigatoni", "400", "pantry"),
                        org.assertj.core.groups.Tuple.tuple("garlic", "3", "produce"));
    }

    /**
     * The response schema pins the enum, but a category outside the vocabulary
     * would create an aisle the list cannot order — a silently broken list
     * rather than an error, so it is worth defending twice.
     */
    @Test
    void foldsAnUnknownCategoryIntoOther() {
        respondWith("""
                {"items":[{"name":"saffron","quantity":"1","unit":"pinch","category":"spice rack"}]}""");

        assertThat(convert()).singleElement()
                .extracting(ShoppingListConverter.ItemDraft::category)
                .isEqualTo("other");
    }

    @Test
    void normalisesCategoryCasingAndPadding() {
        respondWith("""
                {"items":[{"name":"milk","category":"  Dairy And Eggs "}]}""");

        assertThat(convert()).singleElement()
                .extracting(ShoppingListConverter.ItemDraft::category)
                .isEqualTo("dairy and eggs");
    }

    /** A nameless item is not shoppable; a quantity-less one is (buy some garlic). */
    @Test
    void skipsItemsWithNoNameButKeepsOnesWithNoQuantity() {
        respondWith("""
                {"items":[
                  {"name":"","category":"produce"},
                  {"name":"   ","category":"produce"},
                  {"name":"garlic","category":"produce"}
                ]}""");

        assertThat(convert()).singleElement()
                .extracting(ShoppingListConverter.ItemDraft::name).isEqualTo("garlic");
        assertThat(convert().getFirst().quantity()).isNull();
    }

    @Test
    void survivesAMalformedResponseWithoutThrowing() {
        respondWith("{\"unexpected\":true}");

        assertThat(convert()).isEmpty();
    }

    /** No ingredients means no reason to spend a request. */
    @Test
    void doesNotCallTheModelForAnEmptyIngredientList() {
        assertThat(converter.convert(SAVE_ID, "Empty", List.of(), mock(BudgetApproved.class)))
                .isEmpty();

        verify(gemini, never()).generateJson(any(), anyString(), anyString(), any(), anyString(), any());
    }

    /**
     * Aisle order is the whole point of categorising — a list that jumps
     * between produce and frozen and back makes the shopper walk the shop
     * twice.
     */
    @Test
    void sendsTheClosedCategoryVocabularyInTheResponseSchema() {
        respondWith("{\"items\":[]}");
        convert();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini).generateJson(eq(SAVE_ID), anyString(), anyString(), schema.capture(),
                eq("shopping_list"), any());

        assertThat(schema.getValue().toString())
                .contains("produce")
                .contains("dairy and eggs")
                // Ordered as a shop is laid out, not alphabetically.
                .contains("frozen");
        assertThat(ShoppingListConverter.CATEGORIES).startsWith("produce").endsWith("other");
    }

    /** The recipe title is context for ambiguous ingredients ("stock" in what?). */
    @Test
    void sendsTheRecipeTitleAndIngredientsToTheModel() {
        respondWith("{\"items\":[]}");
        convert();

        ArgumentCaptor<String> userText = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(any(), anyString(), userText.capture(), any(), anyString(), any());

        assertThat(userText.getValue())
                .contains("Creamy Tomato Pasta")
                .contains("400g rigatoni")
                .contains("3 cloves garlic, minced");
    }
}
