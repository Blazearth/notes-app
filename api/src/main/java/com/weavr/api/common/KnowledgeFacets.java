package com.weavr.api.common;

import java.util.Map;

/**
 * Which extracted field subdivides each knowledge type, and the product-facing
 * name for each type — shared between {@link com.weavr.api.group.GroupService}
 * (subdivides a type's saves) and {@code CollectionService} (subdivides a
 * type's merged entities). The facet that files a save into a group is the
 * same facet that files it into a collection; two copies would drift.
 *
 * <p>Facet values are a closed-ish vocabulary the model already emits —
 * cuisines and genres repeat across saves, so they cluster. Free-text fields
 * like {@code title} would produce one bucket per save, which is not a
 * grouping at all.
 */
public final class KnowledgeFacets {

    private KnowledgeFacets() {}

    public static final Map<String, String> FACETS = Map.ofEntries(
            Map.entry("recipe", "cuisine"),
            Map.entry("restaurant", "cuisine"),
            Map.entry("movie", "genre"),
            Map.entry("book", "genre"),
            Map.entry("place", "cuisine"),
            Map.entry("article", "category"),
            Map.entry("product", "category"),
            Map.entry("workout", "category"),
            Map.entry("other", "category"),
            Map.entry("recommendation_list", "medium"),
            Map.entry("checklist", "category"),
            Map.entry("itinerary", "destination"),
            Map.entry("course", "subject"),
            Map.entry("github_repo", "language"));

    /**
     * Anything absent falls back to a title-cased version of the raw type,
     * because {@code knowledge_type} is free text from the model — a type this
     * map has never heard of must still get a sensible folder rather than
     * disappearing from the library.
     */
    public static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
            Map.entry("recipe", "Recipes"),
            Map.entry("movie", "Watchlist"),
            Map.entry("place", "Places"),
            Map.entry("restaurant", "Restaurants"),
            Map.entry("product", "Shopping"),
            Map.entry("article", "Reading"),
            Map.entry("workout", "Workouts"),
            Map.entry("book", "Books"),
            Map.entry("other", "Other"),
            Map.entry("recommendation_list", "Recommendations"),
            Map.entry("checklist", "Checklists"),
            Map.entry("itinerary", "Itineraries"),
            Map.entry("course", "Courses"),
            Map.entry("github_repo", "Repos"));

    public static String displayName(String knowledgeType) {
        return DISPLAY_NAMES.getOrDefault(knowledgeType, titleCase(knowledgeType));
    }

    public static String titleCase(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return trimmed;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }
}
