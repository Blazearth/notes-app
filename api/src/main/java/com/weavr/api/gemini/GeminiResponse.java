package com.weavr.api.gemini;

import java.util.Map;

/**
 * The parsed response from a single Gemini classify-and-extract call.
 *
 * @param knowledgeType  one of the registered types, e.g. {@code "recipe"},
 *                       {@code "movie"}, {@code "place"}, {@code "other"},
 *                       or {@code "unusable"} for junk content
 * @param confidence     Gemini's own confidence, 0.0–1.0
 * @param structuredData type-specific fields as a map (matches the schema
 *                       returned by {@link KnowledgeTypeRegistry})
 * @param inputTokens    token count for observability / cost tracking
 * @param outputTokens   token count for observability / cost tracking
 * @param model          which model actually served this response
 */
public record GeminiResponse(
        String knowledgeType,
        double confidence,
        Map<String, Object> structuredData,
        int inputTokens,
        int outputTokens,
        String model
) {
}
