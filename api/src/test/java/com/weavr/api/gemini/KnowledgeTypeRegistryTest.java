package com.weavr.api.gemini;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry builds the exact {@code responseSchema} and system prompt sent
 * to Gemini on every save — a bug here breaks classification silently (Gemini
 * either rejects the schema or the model field names don't match what
 * {@link com.weavr.api.save.ClassifySaveHandler} reads back out).
 */
class KnowledgeTypeRegistryTest {

    @Test
    void knowsEveryRegisteredType() {
        assertThat(KnowledgeTypeRegistry.isKnown("recipe")).isTrue();
        assertThat(KnowledgeTypeRegistry.isKnown("movie")).isTrue();
        assertThat(KnowledgeTypeRegistry.isKnown("place")).isTrue();
        assertThat(KnowledgeTypeRegistry.isKnown("other")).isTrue();
        assertThat(KnowledgeTypeRegistry.isKnown("unusable")).isTrue();
        assertThat(KnowledgeTypeRegistry.isKnown("not_a_real_type")).isFalse();
    }

    @Test
    void getReturnsTheRegisteredTypeByName() {
        KnowledgeTypeRegistry.KnowledgeType recipe = KnowledgeTypeRegistry.get("recipe");

        assertThat(recipe).isNotNull();
        assertThat(recipe.fields()).extracting(KnowledgeTypeRegistry.FieldSpec::name)
                .contains("title", "ingredients", "steps");
    }

    @Test
    void responseSchemaCarriesTheCommonFieldsAtTheTopLevel() {
        Map<String, Object> schema = KnowledgeTypeRegistry.buildResponseSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("knowledgeType", "confidence");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("knowledgeType", "confidence");
    }

    @Test
    void responseSchemaHasOneAnyOfBranchPerRegisteredType() {
        Map<String, Object> schema = KnowledgeTypeRegistry.buildResponseSchema();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) schema.get("anyOf");

        assertThat(anyOf).hasSameSizeAs(KnowledgeTypeRegistry.all());
    }

    @Test
    void everyAnyOfBranchRequiresItsOwnFieldsPlusTheCommonOnes() {
        Map<String, Object> schema = KnowledgeTypeRegistry.buildResponseSchema();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) schema.get("anyOf");

        for (KnowledgeTypeRegistry.KnowledgeType type : KnowledgeTypeRegistry.all()) {
            Map<String, Object> branch = anyOf.stream()
                    .filter(b -> matchesType(b, type.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No anyOf branch for " + type.name()));

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) branch.get("required");
            assertThat(required).contains("knowledgeType", "confidence");
            for (KnowledgeTypeRegistry.FieldSpec field : type.fields()) {
                assertThat(required).as("required field '%s' for type '%s'", field.name(), type.name())
                        .contains(field.name());
            }
        }
    }

    @Test
    void arrayFieldsGetStringItemsInTheSchema() {
        KnowledgeTypeRegistry.KnowledgeType recipe = KnowledgeTypeRegistry.get("recipe");
        Map<String, Object> schema = KnowledgeTypeRegistry.buildResponseSchema();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) schema.get("anyOf");
        Map<String, Object> recipeBranch = anyOf.stream()
                .filter(b -> matchesType(b, "recipe"))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) recipeBranch.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> ingredients = (Map<String, Object>) properties.get("ingredients");

        assertThat(recipe.fields()).extracting(KnowledgeTypeRegistry.FieldSpec::name).contains("ingredients");
        assertThat(ingredients.get("type")).isEqualTo("array");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) ingredients.get("items");
        assertThat(items.get("type")).isEqualTo("string");
    }

    @Test
    void systemPromptMentionsEveryTypeAndItsFewShotExample() {
        String prompt = KnowledgeTypeRegistry.buildSystemPrompt();

        for (KnowledgeTypeRegistry.KnowledgeType type : KnowledgeTypeRegistry.all()) {
            assertThat(prompt).contains(type.name());
            assertThat(prompt).contains(type.description());
        }
        // The [unclear] sentinel contract must survive edits to the prompt copy.
        assertThat(prompt).contains("[unclear]");
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesType(Map<String, Object> branch, String typeName) {
        Map<String, Object> properties = (Map<String, Object>) branch.get("properties");
        Map<String, Object> knowledgeType = (Map<String, Object>) properties.get("knowledgeType");
        List<String> enumValues = (List<String>) knowledgeType.get("enum");
        return enumValues.contains(typeName);
    }
}
