package com.weavr.api.embed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually gets embedded (CA#8).
 *
 * <p>Every assertion here is about keeping noise <em>out</em> of the vector.
 * There is no error when this goes wrong — the save is still embedded, still
 * searchable, and simply lands in the wrong neighbourhood, which is invisible
 * without a relevance benchmark.
 */
class EmbeddingProfileTest {

    private static Map<String, Object> recipe() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "Creamy Tomato Pasta");
        data.put("servings", "4");
        data.put("cuisine", "Italian");
        data.put("ingredients", List.of("400g rigatoni", "2 tbsp olive oil", "1 cup heavy cream"));
        data.put("dietaryNotes", List.of());
        return data;
    }

    @Test
    void leadsWithTheKnowledgeTypeThenLabelledFields() {
        String profile = EmbeddingProfile.build("recipe", recipe(), null, 8000);

        assertThat(profile.lines().toList().getFirst()).isEqualTo("recipe");
        assertThat(profile)
                .contains("Title: Creamy Tomato Pasta")
                .contains("Cuisine: Italian")
                .contains("Ingredients: 400g rigatoni, 2 tbsp olive oil, 1 cup heavy cream");
    }

    /**
     * "[unclear]" is the registry's sentinel for genuinely absent information
     * and appears in most saves. Embedded, it becomes a term every sparse save
     * shares — making sparse saves similar to each other rather than to
     * anything relevant.
     */
    @Test
    void dropsTheUnclearSentinelRatherThanEmbeddingIt() {
        Map<String, Object> data = new LinkedHashMap<>(recipe());
        data.put("prepTime", "[unclear]");
        data.put("dietaryNotes", List.of("vegan", "[unclear]"));

        String profile = EmbeddingProfile.build("recipe", data, null, 8000);

        assertThat(profile).doesNotContain("[unclear]").doesNotContain("Prep time");
        assertThat(profile).contains("Dietary notes: vegan");
    }

    @Test
    void omitsEmptyCollectionsAndBlankValues() {
        String profile = EmbeddingProfile.build("recipe", recipe(), null, 8000);

        assertThat(profile).doesNotContain("Dietary notes");
    }

    /**
     * Ratings and price bands are formats, not subjects: "8.5/10" and "$$"
     * cluster saves by how they were written rather than by what they are.
     */
    @Test
    void skipsBookkeepingAndFormatOnlyFields() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Noma");
        data.put("rating", "9.5/10");
        data.put("priceRange", "$$$$");
        data.put("confidence", 0.96);
        data.put("modelUsed", "gemini-3.1-flash-lite");

        String profile = EmbeddingProfile.build("place", data, null, 8000);

        assertThat(profile).contains("Name: Noma");
        assertThat(profile)
                .doesNotContain("9.5/10")
                .doesNotContain("$$$$")
                .doesNotContain("0.96")
                .doesNotContain("flash-lite");
    }

    /** A card that lists the same thing twice should not weight it twice. */
    @Test
    void deduplicatesRepeatedCollectionValuesButKeepsOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ingredients", List.of("salt", "pepper", "salt"));

        assertThat(EmbeddingProfile.build("recipe", data, null, 8000))
                .contains("Ingredients: salt, pepper");
    }

    /**
     * An unclassified or `unusable` save has no structured fields. Embedding
     * the raw text is worse than a profile but far better than leaving the row
     * out of similarity search entirely.
     */
    @Test
    void fallsBackToRawTextOnlyWhenThereIsNoStructuredData() {
        String profile = EmbeddingProfile.build("other", Map.of(),
                "some   loose\n\nnotes about   sourdough", 8000);

        assertThat(profile).contains("some loose notes about sourdough");
    }

    @Test
    void prefersStructuredDataOverTheRawFallback() {
        String profile = EmbeddingProfile.build("recipe", recipe(), "raw caption noise", 8000);

        assertThat(profile).doesNotContain("raw caption noise");
    }

    @Test
    void isEmptyWhenThereIsNothingAtAll() {
        assertThat(EmbeddingProfile.build(null, Map.of(), null, 8000)).isEmpty();
    }

    @Test
    void capsTheProfileLength() {
        Map<String, Object> data = Map.of("summary", "x".repeat(500));

        assertThat(EmbeddingProfile.build("other", data, null, 100)).hasSize(100);
    }

    @Test
    void turnsCamelCaseFieldNamesIntoReadableLabels() {
        assertThat(EmbeddingProfile.label("dietaryNotes")).isEqualTo("Dietary notes");
        assertThat(EmbeddingProfile.label("whereTo")).isEqualTo("Where to");
        assertThat(EmbeddingProfile.label("title")).isEqualTo("Title");
    }
}
