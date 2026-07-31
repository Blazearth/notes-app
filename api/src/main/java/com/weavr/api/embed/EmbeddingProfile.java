package com.weavr.api.embed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a save's {@code structured_data} into the short {@code label: value}
 * text that actually gets embedded (CA#8).
 *
 * <p><b>Embed the structured profile, not the raw text.</b> The raw blob is a
 * caption, a transcript or OCR output — it is dominated by filler, hashtags,
 * "link in bio", and whatever the speaker said in the first ten seconds. An
 * embedding of that is an embedding of the noise. The extraction step has
 * already done the work of deciding what this save *is*, and a profile built
 * from its output puts the discriminating terms next to each other with nothing
 * in between:
 *
 * <pre>
 *   recipe
 *   Title: Creamy Tomato Pasta
 *   Cuisine: Italian
 *   Ingredients: rigatoni, olive oil, heavy cream, garlic
 *   Steps: brown the garlic; add cream; toss with pasta
 * </pre>
 *
 * <p>Two details that matter more than they look:
 *
 * <ul>
 *   <li><b>The knowledge type leads.</b> "recipe" and "place" are the strongest
 *       single tokens available for separating clusters, and putting the type
 *       first means a nearest-neighbour query is biased toward same-type
 *       results before any field is compared.</li>
 *   <li><b>{@code [unclear]} is dropped, not embedded.</b> It is the registry's
 *       sentinel for "genuinely not in the content", and it appears in most
 *       saves. Embedded, it becomes a term that every sparse save shares, which
 *       makes sparse saves look similar to each other rather than to anything
 *       relevant.</li>
 * </ul>
 */
public final class EmbeddingProfile {

    /** The registry's sentinel for missing information — never worth embedding. */
    private static final String UNCLEAR = "[unclear]";

    /**
     * Fields that carry no meaning for similarity, only bookkeeping. Ratings and
     * prices are numbers whose textual form ("8.5/10", "$$") clusters saves by
     * format rather than by subject.
     */
    private static final Set<String> SKIPPED = Set.of(
            "confidence", "knowledgeType", "modelUsed", "rating", "priceRange");

    private EmbeddingProfile() {
    }

    /**
     * @param knowledgeType  the classified type, or null
     * @param structuredData the model's extracted fields
     * @param fallbackText   used only when structured data yields nothing —
     *                       a save can be embedded before or without a
     *                       successful classification
     * @param maxChars       hard cap on the result
     */
    public static String build(String knowledgeType, Map<String, Object> structuredData,
                               String fallbackText, int maxChars) {
        List<String> lines = new ArrayList<>();

        if (knowledgeType != null && !knowledgeType.isBlank()) {
            lines.add(knowledgeType);
        }

        if (structuredData != null) {
            for (Map.Entry<String, Object> field : structuredData.entrySet()) {
                if (SKIPPED.contains(field.getKey())) {
                    continue;
                }
                String value = render(field.getValue());
                if (value.isBlank()) {
                    continue;
                }
                lines.add(label(field.getKey()) + ": " + value);
            }
        }

        // Nothing structured to work with — an unclassified or `unusable` save.
        // Embedding the raw text is worse than embedding a profile, but it is
        // much better than leaving the row unsearchable by similarity at all.
        if (lines.size() <= 1 && fallbackText != null && !fallbackText.isBlank()) {
            lines.add(fallbackText.replaceAll("\\s+", " ").strip());
        }

        String profile = String.join("\n", lines).strip();
        return profile.length() <= maxChars ? profile : profile.substring(0, maxChars);
    }

    /** {@code dietaryNotes} → {@code Dietary notes}. Readable, and cheap. */
    static String label(String field) {
        String spaced = field.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase();
    }

    /**
     * Collections join with commas; everything else is its own text. Nested
     * maps are rendered by {@code toString} rather than recursed into — no v1
     * knowledge type has one, and inventing a traversal for a shape that does
     * not exist yet would be guessing at its eventual form.
     */
    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> items) {
            // LinkedHashSet: a card that repeats an ingredient should not
            // weight that ingredient twice, but order still carries meaning.
            Set<String> rendered = new LinkedHashSet<>();
            for (Object item : items) {
                String text = String.valueOf(item).strip();
                if (!text.isEmpty() && !UNCLEAR.equalsIgnoreCase(text)) {
                    rendered.add(text);
                }
            }
            return String.join(", ", rendered);
        }
        String text = String.valueOf(value).strip();
        return UNCLEAR.equalsIgnoreCase(text) ? "" : text;
    }
}
