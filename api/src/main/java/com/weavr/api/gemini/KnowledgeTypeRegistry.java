package com.weavr.api.gemini;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of knowledge types: their JSON response schemas and few-shot
 * examples for the Gemini classify-and-extract call.
 *
 * <p>Adding a knowledge type is a data change (add an entry here), not a code
 * change. The registry builds the {@code anyOf} response schema sent to Gemini
 * so the model returns structured JSON directly — no free-text parsing.
 *
 * <p>Design choices:
 * <ul>
 *   <li>Tokens are cheap, requests are scarce — few-shot examples are worth
 *       their token cost to avoid a low-confidence second request.</li>
 *   <li>{@code unusable} is a first-class type for error pages, blocked
 *       downloads, and genuinely empty content. Returning it is a success,
 *       not a failure.</li>
 *   <li>Fields use {@code [unclear]} as the sentinel for genuinely missing
 *       information, distinguishing "not in the content" from "not extracted."
 *       </li>
 * </ul>
 */
public final class KnowledgeTypeRegistry {

    /** All registered types, in insertion order. */
    private static final Map<String, KnowledgeType> TYPES = new LinkedHashMap<>();

    static {
        // -----------------------------------------------------------------
        // recipe
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "recipe",
                "A cooking recipe with ingredients and steps.",
                List.of(
                        field("title", "string", "Recipe title, e.g. 'Classic Tiramisu'"),
                        field("servings", "string", "Number of servings, e.g. '4' or '4-6'"),
                        field("prepTime", "string", "Prep time, e.g. '15 min' or '[unclear]'"),
                        field("cookTime", "string", "Cook/bake time, e.g. '30 min' or '[unclear]'"),
                        field("ingredients", "array", "List of ingredients with quantities"),
                        field("steps", "array", "Ordered list of preparation steps"),
                        field("cuisine", "string", "Cuisine type, e.g. 'Italian' or '[unclear]'"),
                        field("dietaryNotes", "array", "e.g. ['gluten-free', 'vegan'], or []")
                ),
                """
                Input: "3 eggs, 250g mascarpone, 2 tbsp sugar. Beat yolks with sugar. \
                Fold in mascarpone. Layer with coffee-soaked ladyfingers. Chill 4 hours."
                Output:
                {
                  "knowledgeType": "recipe",
                  "confidence": 0.97,
                  "title": "Tiramisu",
                  "servings": "[unclear]",
                  "prepTime": "15 min",
                  "cookTime": "0 min",
                  "ingredients": ["3 eggs", "250g mascarpone", "2 tbsp sugar", "ladyfingers", "coffee"],
                  "steps": ["Beat egg yolks with sugar", "Fold in mascarpone", "Dip ladyfingers in coffee", "Layer and chill 4 hours"],
                  "cuisine": "Italian",
                  "dietaryNotes": []
                }
                """
        ));

        // -----------------------------------------------------------------
        // movie / TV show
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "movie",
                "A film or TV show recommendation or review.",
                List.of(
                        field("title", "string", "Film or show title"),
                        field("year", "string", "Release year, e.g. '2023' or '[unclear]'"),
                        field("director", "string", "Director name or '[unclear]'"),
                        field("genre", "array", "e.g. ['thriller', 'sci-fi']"),
                        field("rating", "string", "Rating or score if mentioned, e.g. '8.5/10' or '[unclear]'"),
                        field("synopsis", "string", "One-sentence summary of what it's about"),
                        field("whereTo", "string", "Streaming platform if mentioned, e.g. 'Netflix' or '[unclear]'")
                ),
                """
                Input: "Oppenheimer (2023) by Christopher Nolan. Stunning IMAX experience. \
                The story of J. Robert Oppenheimer and the Manhattan Project. On Peacock now."
                Output:
                {
                  "knowledgeType": "movie",
                  "confidence": 0.95,
                  "title": "Oppenheimer",
                  "year": "2023",
                  "director": "Christopher Nolan",
                  "genre": ["drama", "historical", "thriller"],
                  "rating": "[unclear]",
                  "synopsis": "The story of J. Robert Oppenheimer and the development of the atomic bomb.",
                  "whereTo": "Peacock"
                }
                """
        ));

        // -----------------------------------------------------------------
        // place (restaurant, café, attraction)
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "place",
                "A restaurant, café, bar, hotel, or place to visit.",
                List.of(
                        field("name", "string", "Place name"),
                        field("type", "string", "e.g. 'restaurant', 'café', 'hotel', 'attraction'"),
                        field("address", "string", "Address or area, or '[unclear]'"),
                        field("cuisine", "string", "For food: cuisine type, or '[unclear]'"),
                        field("priceRange", "string", "e.g. '$$', 'budget', 'expensive', or '[unclear]'"),
                        field("highlights", "array", "Key features or dishes mentioned"),
                        field("rating", "string", "Rating or score if mentioned, or '[unclear]'")
                ),
                """
                Input: "Went to Noma in Copenhagen. $$$$ but unforgettable. New Nordic cuisine. \
                Fermented everything. The reindeer moss dish was insane. Book 3 months ahead."
                Output:
                {
                  "knowledgeType": "place",
                  "confidence": 0.96,
                  "name": "Noma",
                  "type": "restaurant",
                  "address": "Copenhagen",
                  "cuisine": "New Nordic",
                  "priceRange": "$$$$",
                  "highlights": ["fermented dishes", "reindeer moss dish", "requires 3-month advance booking"],
                  "rating": "[unclear]"
                }
                """
        ));

        // -----------------------------------------------------------------
        // other — anything that doesn't fit a specific type
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "other",
                "Interesting content that doesn't fit a specific category.",
                List.of(
                        field("title", "string", "A descriptive title for this save"),
                        field("summary", "string", "2-3 sentence summary of what this is about"),
                        field("tags", "array", "Relevant topic tags, e.g. ['productivity', 'design']")
                ),
                """
                Input: "10 rules for writing great software: 1. Make it work, 2. Make it right, \
                3. Make it fast. Premature optimization is the root of all evil. ..."
                Output:
                {
                  "knowledgeType": "other",
                  "confidence": 0.88,
                  "title": "10 Rules for Writing Great Software",
                  "summary": "A guide to software engineering principles, emphasizing correctness before optimization and simplicity over complexity.",
                  "tags": ["software engineering", "programming", "best practices"]
                }
                """
        ));

        // -----------------------------------------------------------------
        // unusable — error pages, blocked, empty, or truly unclassifiable
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "unusable",
                "Content that cannot be classified: error pages, blocked downloads, empty content, or meaningless text.",
                List.of(
                        field("reason", "string", "Why the content is unusable, e.g. 'Error 404 page', 'login wall', 'empty transcript'")
                ),
                """
                Input: "404 Not Found. The page you requested could not be found."
                Output:
                {
                  "knowledgeType": "unusable",
                  "confidence": 1.0,
                  "reason": "Error 404 page — content not available"
                }
                """
        ));
    }

    private KnowledgeTypeRegistry() {
    }

    private static void register(KnowledgeType type) {
        TYPES.put(type.name(), type);
    }

    public static Collection<KnowledgeType> all() {
        return TYPES.values();
    }

    public static KnowledgeType get(String name) {
        return TYPES.get(name);
    }

    public static boolean isKnown(String name) {
        return TYPES.containsKey(name);
    }

    /**
     * Builds the Gemini {@code responseSchema} as a plain Java map.
     * The outer wrapper holds {@code knowledgeType} and {@code confidence}
     * (common to all types), plus the type-specific fields as an {@code anyOf}.
     */
    public static Map<String, Object> buildResponseSchema() {
        // anyOf: one schema per type, each with a fixed knowledgeType enum value.
        List<Map<String, Object>> anyOf = TYPES.values().stream()
                .map(KnowledgeTypeRegistry::schemaForType)
                .toList();

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "knowledgeType", Map.of(
                                "type", "string",
                                "enum", TYPES.keySet().stream().toList()
                        ),
                        "confidence", Map.of(
                                "type", "number",
                                "description", "0.0–1.0, how certain the classification is"
                        )
                ),
                "required", List.of("knowledgeType", "confidence"),
                "anyOf", anyOf
        );
    }

    /**
     * Builds the system prompt. Includes:
     * <ul>
     *   <li>Classification instructions</li>
     *   <li>One few-shot example per type</li>
     *   <li>The [unclear] sentinel contract</li>
     * </ul>
     */
    public static String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a content classification and extraction assistant for Weavr, \
                a personal knowledge app.

                Your job: classify the provided content into exactly one knowledge type, \
                then extract all available structured fields for that type.

                Rules:
                - Return valid JSON matching the schema exactly.
                - Use "[unclear]" (the literal string) for string fields where \
                  the information is genuinely not present in the content. \
                  Never guess or hallucinate facts.
                - Use [] for array fields where no items are mentioned.
                - Set knowledgeType to "unusable" for error pages, login walls, \
                  blocked downloads, empty content, or anything not classifiable.
                - Set confidence to 1.0 only when you are certain. Use lower values \
                  when the content is ambiguous.
                - Whitespace-normalize the input before extracting — ignore formatting \
                  artifacts from VTT captions or OCR.

                Knowledge types:
                """);

        for (KnowledgeType type : TYPES.values()) {
            sb.append("- ").append(type.name()).append(": ").append(type.description()).append("\n");
        }

        sb.append("\nFew-shot examples:\n");
        for (KnowledgeType type : TYPES.values()) {
            sb.append("\n### ").append(type.name()).append("\n");
            sb.append(type.example()).append("\n");
        }

        return sb.toString();
    }

    private static Map<String, Object> schemaForType(KnowledgeType type) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("knowledgeType", Map.of("type", "string", "enum", List.of(type.name())));
        props.put("confidence", Map.of("type", "number"));
        for (FieldSpec f : type.fields()) {
            props.put(f.name(), fieldSchema(f));
        }
        List<String> required = type.fields().stream()
                .map(FieldSpec::name)
                .toList();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        List<String> allRequired = new java.util.ArrayList<>();
        allRequired.add("knowledgeType");
        allRequired.add("confidence");
        allRequired.addAll(required);
        schema.put("required", allRequired);
        return schema;
    }

    private static Map<String, Object> fieldSchema(FieldSpec f) {
        if ("array".equals(f.type())) {
            return Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", f.description()
            );
        }
        return Map.of("type", f.type(), "description", f.description());
    }

    private static FieldSpec field(String name, String type, String description) {
        return new FieldSpec(name, type, description);
    }

    /** A single field in a knowledge type's schema. */
    public record FieldSpec(String name, String type, String description) {
    }

    /** A registered knowledge type with its schema and few-shot example. */
    public record KnowledgeType(
            String name,
            String description,
            List<FieldSpec> fields,
            String example
    ) {
    }
}
