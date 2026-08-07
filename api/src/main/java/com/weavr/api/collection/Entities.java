package com.weavr.api.collection;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The entity key: a deterministic, pure function from an item's {@code kind}
 * and {@code name} to the identity three romance-anime Reels merge on. See
 * {@code docs/knowledge-collections.md} ("The entity key") for the design.
 *
 * <p>Static and database-free for the same reason {@code ShoppingListService
 * .fold} and {@code GroupService.buildTree} are: the merge property has to be
 * unit-testable with no Spring context and no real saves.
 *
 * <p><b>Known limit, stated up front, not discovered later:</b> this does not
 * resolve aliases — "Shingeki no Kyojin" and "Attack on Titan" collide under
 * no string rule here. K0 measured this against the two real
 * {@code recommendation_list} saves in the dev database (7 items, zero
 * overlapping titles) and found nothing to contradict the rules below, but it
 * could not exercise true-duplicate detection either, since none of those
 * items actually repeat. Canonical ids from enrichment (K4, {@link
 * #key(String, String, String)}) are the answer, not a fuzzier string rule
 * here.
 */
final class Entities {

    private Entities() {}

    /** {@code kind}/{@code medium} values that collapse into one namespace, so a game and a film sharing a title don't collide. */
    private static final Map<String, String> KIND_NAMESPACES = Map.ofEntries(
            Map.entry("film", "screen"),
            Map.entry("movie", "screen"),
            Map.entry("tv", "screen"),
            Map.entry("series", "screen"),
            Map.entry("anime", "screen"),
            Map.entry("show", "screen"),
            Map.entry("book", "book"),
            Map.entry("game", "game"),
            Map.entry("place", "place"),
            Map.entry("sight", "place"),
            Map.entry("restaurant", "place"),
            Map.entry("hotel", "place"),
            Map.entry("area", "place"),
            Map.entry("product", "product"),
            Map.entry("music", "audio"),
            Map.entry("podcast", "audio"),
            Map.entry("task", "task"));

    private static final Set<String> LEADING_ARTICLES = Set.of("the", "a", "an");

    /**
     * {@code kindNamespace + ":" + normalize(name)}. Two items collide iff they
     * share a coarse kind namespace and a normalized name — "Blue Box (anime)"
     * from a watchlist item and "Blue Box (series)" from a full review both
     * resolve to {@code screen:blue box}, on purpose.
     */
    static String key(String kind, String name) {
        return key(kind, name, null);
    }

    /**
     * K4: when a canonical id is available — {@code tmdbId}, written by
     * {@code TmdbEnricher}/{@code RecommendationListEnricher} on a confident
     * match — it <em>overrides</em> the string key entirely, because it is
     * exactly the alias fix stated as a known limit above: "Shingeki no
     * Kyojin" and "Attack on Titan" both resolving to the same TMDB id
     * collide here even though no normalization rule would ever unify their
     * strings. {@code canonicalId} is scoped by source (prefixed
     * {@code "tmdb:"}) rather than trusted bare, so a future second id
     * source cannot collide with this one by numeric accident.
     */
    static String key(String kind, String name, String canonicalId) {
        if (canonicalId != null && !canonicalId.isBlank()) {
            return "tmdb:" + canonicalId.trim();
        }
        return namespace(kind) + ":" + normalize(name);
    }

    /** Package-visible so a test can pin the coarsening table directly, not just through {@link #key}. */
    static String namespace(String kind) {
        if (kind == null) return "other";
        String trimmed = kind.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "other";
        return KIND_NAMESPACES.getOrDefault(trimmed, trimmed);
    }

    /**
     * Unicode NFKC → casefold → trim → collapse whitespace → strip surrounding
     * punctuation → strip a leading article. Exactly the doc's spec, frozen by
     * K0 against the two real saves in the dev database — see the class
     * Javadoc for what that did and did not prove.
     */
    static String normalize(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC);
        s = s.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("^[\\p{Punct}\\s]+", "").replaceAll("[\\p{Punct}\\s]+$", "");

        int firstSpace = s.indexOf(' ');
        if (firstSpace > 0 && LEADING_ARTICLES.contains(s.substring(0, firstSpace))) {
            s = s.substring(firstSpace + 1);
        }
        return s;
    }
}
