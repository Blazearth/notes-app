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
                        objectArray("ingredients", "Every ingredient, in the order listed",
                                field("name", "string", "The ingredient itself, e.g. 'mascarpone' — no quantity, no preparation"),
                                field("quantity", "string", "Amount as written, unit included, e.g. '250g' or '2 tbsp', or '[unclear]'"),
                                field("note", "string", "Preparation or qualifier, e.g. 'minced', 'optional', or '[unclear]'")),
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
                  "ingredients": [
                    {"name": "eggs", "quantity": "3", "note": "[unclear]"},
                    {"name": "mascarpone", "quantity": "250g", "note": "[unclear]"},
                    {"name": "sugar", "quantity": "2 tbsp", "note": "[unclear]"},
                    {"name": "ladyfingers", "quantity": "[unclear]", "note": "[unclear]"},
                    {"name": "coffee", "quantity": "[unclear]", "note": "for soaking"}
                  ],
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
        // article / blog post / tutorial
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "article",
                "A written article, blog post, essay, tutorial, or guide.",
                List.of(
                        field("title", "string", "Article title"),
                        field("summary", "string", "2-3 sentence summary of the main point"),
                        field("category", "string",
                                "One of: Technology, Health, Finance, Science, Design, " +
                                "Business, Politics, Sports, Entertainment, Lifestyle, Education, Other"),
                        field("tags", "array", "Up to 3 specific topic tags, e.g. ['machine learning', 'Python']")
                ),
                """
                Input: "How I optimised my React app from 8s to 1.2s load time. \
                Key wins: lazy loading, code splitting, and moving to a CDN. \
                Full walkthrough with benchmarks."
                Output:
                {
                  "knowledgeType": "article",
                  "confidence": 0.93,
                  "title": "How I Optimised My React App from 8s to 1.2s",
                  "summary": "A practical walkthrough of frontend performance optimisation techniques including lazy loading, code splitting, and CDN migration, with before/after benchmarks.",
                  "category": "Technology",
                  "tags": ["React", "performance", "web development"]
                }
                """
        ));

        // -----------------------------------------------------------------
        // product — something to buy or already bought
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "product",
                "A product, gadget, tool, or item to buy or that was reviewed.",
                List.of(
                        field("title", "string", "Product name and model if known"),
                        field("summary", "string", "One sentence on what it is and why it's notable"),
                        field("category", "string",
                                "One of: Electronics, Clothing, Books, Home, Kitchen, " +
                                "Sports, Beauty, Toys, Software, Other"),
                        field("price", "string", "Price if mentioned, e.g. '$49' or '[unclear]'"),
                        field("whereTo", "string", "Where to buy if mentioned, or '[unclear]'")
                ),
                """
                Input: "The Kindle Paperwhite 2024 — 7 inch 300ppi display, 16GB, waterproof. \
                $139 on Amazon. Best e-reader for the price, hands down."
                Output:
                {
                  "knowledgeType": "product",
                  "confidence": 0.95,
                  "title": "Kindle Paperwhite 2024",
                  "summary": "A 7-inch waterproof e-reader with 300ppi display and 16GB storage, widely regarded as the best value e-reader.",
                  "category": "Electronics",
                  "price": "$139",
                  "whereTo": "Amazon"
                }
                """
        ));

        // -----------------------------------------------------------------
        // book
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "book",
                "A book recommendation, review, or reading note.",
                List.of(
                        field("title", "string", "Book title"),
                        field("author", "string", "Author name or '[unclear]'"),
                        field("genre", "array", "e.g. ['non-fiction', 'psychology']"),
                        field("summary", "string", "One-sentence description of what the book is about"),
                        field("rating", "string", "Rating or score if mentioned, or '[unclear]'")
                ),
                """
                Input: "Finished Atomic Habits by James Clear. Changed how I think about \
                building routines. Core idea: 1% better every day compounds massively. 5/5."
                Output:
                {
                  "knowledgeType": "book",
                  "confidence": 0.96,
                  "title": "Atomic Habits",
                  "author": "James Clear",
                  "genre": ["non-fiction", "self-help", "psychology"],
                  "summary": "A practical guide to building good habits and breaking bad ones through small, incremental daily improvements.",
                  "rating": "5/5"
                }
                """
        ));

        // -----------------------------------------------------------------
        // workout / fitness
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "workout",
                "A workout routine, exercise plan, or fitness content — extract the full routine so it can be followed in the gym.",
                List.of(
                        field("title", "string", "Workout name or type"),
                        field("summary", "string", "One sentence on what the workout involves"),
                        field("category", "string",
                                "One of: Strength, Cardio, Yoga, HIIT, Stretching, Sports, Other"),
                        field("goal", "string", "Stated goal, e.g. 'hypertrophy', 'fat loss', or '[unclear]'"),
                        field("muscleGroups", "array", "Muscle groups trained, e.g. ['chest', 'triceps'], or []"),
                        field("duration", "string", "Duration if mentioned, e.g. '30 min' or '[unclear]'"),
                        field("difficulty", "string",
                                "Only if the content states it, e.g. 'beginner' — never inferred; '[unclear]' otherwise"),
                        field("equipment", "array", "Equipment needed, e.g. ['dumbbells', 'resistance band'], or []"),
                        field("warmup", "array", "Warm-up steps if given, or []"),
                        objectArray("exercises", "Every exercise, in the order performed",
                                field("name", "string", "Exercise name, e.g. 'Incline dumbbell press'"),
                                field("sets", "string", "Number of sets, e.g. '3', or '[unclear]'"),
                                field("reps", "string", "Reps or work time, e.g. '8-12' or '40s', or '[unclear]'"),
                                field("rest", "string", "Rest between sets, e.g. '90s', or '[unclear]'"),
                                field("tempo", "string", "Tempo if mentioned, e.g. '3-1-1', or '[unclear]'"),
                                field("cues", "array", "Technique and execution cues mentioned for this exercise, or []"),
                                field("alternatives", "array", "Substitute exercises if mentioned, or []")),
                        field("cooldown", "array", "Cool-down steps if given, or []"),
                        field("progression", "string", "Progression advice if given, or '[unclear]'"),
                        field("warnings", "array", "Safety warnings or common mistakes called out, or []")
                ),
                """
                Input: "Science-based push day. Start with 5 min band shoulder warm-up. \
                Flat DB press 4x8-10, 2 min rest — control the negative, don't flare elbows. \
                No dumbbells? Barbell bench works. Then cable flys 3x12-15, 60s rest, \
                squeeze at the peak. Add a rep each week. Skip flys if your shoulder clicks."
                Output:
                {
                  "knowledgeType": "workout",
                  "confidence": 0.95,
                  "title": "Science-Based Push Day",
                  "summary": "A chest-focused push workout of dumbbell pressing and cable flys with prescribed sets, reps, and rest.",
                  "category": "Strength",
                  "goal": "[unclear]",
                  "muscleGroups": ["chest", "shoulders", "triceps"],
                  "duration": "[unclear]",
                  "difficulty": "[unclear]",
                  "equipment": ["dumbbells", "cable machine", "resistance band"],
                  "warmup": ["5 min band shoulder warm-up"],
                  "exercises": [
                    {
                      "name": "Flat dumbbell press",
                      "sets": "4",
                      "reps": "8-10",
                      "rest": "2 min",
                      "tempo": "[unclear]",
                      "cues": ["control the negative", "don't flare elbows"],
                      "alternatives": ["Barbell bench press"]
                    },
                    {
                      "name": "Cable flys",
                      "sets": "3",
                      "reps": "12-15",
                      "rest": "60s",
                      "tempo": "[unclear]",
                      "cues": ["squeeze at the peak"],
                      "alternatives": []
                    }
                  ],
                  "cooldown": [],
                  "progression": "Add a rep each week",
                  "warnings": ["Skip cable flys if your shoulder clicks"]
                }
                """
        ));

        // -----------------------------------------------------------------
        // other — anything that doesn't fit a specific type
        // -----------------------------------------------------------------
        register(new KnowledgeType(
                "other",
                "Interesting content that doesn't fit any of the above types.",
                List.of(
                        field("title", "string", "A descriptive title for this save"),
                        field("summary", "string", "2-3 sentence summary of what this is about"),
                        field("category", "string",
                                "One of: Technology, Health, Finance, Science, Design, " +
                                "Business, Politics, Sports, Entertainment, Lifestyle, Education, Other"),
                        field("tags", "array", "Up to 3 specific topic tags, e.g. ['productivity', 'design']")
                ),
                """
                Input: "The Feynman Technique: to learn anything deeply, explain it in simple \
                language as if teaching a child. Where you get stuck is where you don't really \
                understand it yet. Go back to the source, fill the gap, repeat."
                Output:
                {
                  "knowledgeType": "other",
                  "confidence": 0.88,
                  "title": "The Feynman Technique for Deep Learning",
                  "summary": "A learning method where you explain a concept in simple language to expose gaps in your understanding, then fill those gaps by revisiting the source material.",
                  "category": "Education",
                  "tags": ["learning", "productivity", "mental models"]
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
                - Classify by what the user will most likely DO with this content \
                  later, not by its surface format. A workout video is a routine \
                  to follow in the gym; a recipe Reel is a dish to cook. Extract \
                  toward that use: prefer the complete, structured, actionable \
                  form over a faithful prose summary — without ever inventing \
                  facts that are not in the content.
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
        if ("objectArray".equals(f.type())) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (FieldSpec sub : f.fields()) {
                props.put(sub.name(), fieldSchema(sub));
            }
            return Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", props,
                            // Every sub-field required, same contract as the top
                            // level: strings use [unclear], arrays use [].
                            "required", f.fields().stream().map(FieldSpec::name).toList()
                    ),
                    "description", f.description()
            );
        }
        return Map.of("type", f.type(), "description", f.description());
    }

    private static FieldSpec field(String name, String type, String description) {
        return new FieldSpec(name, type, description, List.of());
    }

    /**
     * An array of nested objects — a workout's exercises, a recipe's
     * ingredients. One level of structure the flat string-array could not
     * carry; sub-fields may themselves be strings or string-arrays (deeper
     * nesting works by construction but no type needs it yet).
     */
    private static FieldSpec objectArray(String name, String description, FieldSpec... fields) {
        return new FieldSpec(name, "objectArray", description, List.of(fields));
    }

    /** A single field in a knowledge type's schema. {@code fields} is empty unless {@code type} is {@code objectArray}. */
    public record FieldSpec(String name, String type, String description, List<FieldSpec> fields) {
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
