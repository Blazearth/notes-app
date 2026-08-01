package com.weavr.api.enrich;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Decides whether an external API's top result is actually the thing we asked
 * about.
 *
 * <h2>Why this exists at all</h2>
 * <p>Every one of these search APIs is a nearest-match: TMDB will happily
 * return a film for {@code "nigatoni"} and Google Places a café for a garbled
 * OCR line, because "no result" is a thing they almost never say. Taking the
 * top hit on faith would then write a confident, wrong director onto a save —
 * and enrichment carries more authority than extraction does, because it looks
 * like it came from a database rather than from a Reel.
 *
 * <p>This is the same failure the semantic search half hit: a k-NN query has no
 * concept of "no match" and returned the user's whole library for
 * {@code zzzzqqq} until a distance cutoff was added. Same shape, same fix — a
 * threshold, applied to a similarity that is measured rather than assumed.
 *
 * <p>Static and dependency-free so the threshold can be exercised against real
 * strings without a network.
 */
final class TitleMatch {

    /**
     * Minimum token overlap for a result to be accepted.
     *
     * <p>0.6 keeps "Oppenheimer" ↔ "Oppenheimer" (1.0) and
     * "The Bear" ↔ "The Bear" (1.0), keeps a subtitle difference like
     * "Dune" ↔ "Dune: Part Two" (1.0 of the query's tokens), and rejects
     * "Noma" ↔ "Nomad Coffee" (0.0). Like the OCR confidence floor and the
     * search distance cutoff, it is a starting point argued from examples, not
     * a measured number — which is why it is one constant in one place.
     */
    static final double MIN_SIMILARITY = 0.6;

    private TitleMatch() {
    }

    /**
     * Fraction of the query's words that appear in the candidate.
     *
     * <p>Deliberately asymmetric: the external source usually knows a longer,
     * more official name than a caption does ("Dune" against "Dune: Part Two",
     * "Noma" against "Noma — Restaurant"), and penalising it for that would
     * reject the correct answer. Extra words in the *query* are the suspicious
     * direction, and those do lower the score.
     */
    static double similarity(String query, String candidate) {
        String[] queryWords = normalise(query).split(" ");
        String normalisedCandidate = " " + normalise(candidate) + " ";
        if (queryWords.length == 0 || normalisedCandidate.isBlank()) {
            return 0.0;
        }

        int matched = 0;
        int counted = 0;
        for (String word : queryWords) {
            if (word.isEmpty()) {
                continue;
            }
            counted++;
            if (normalisedCandidate.contains(" " + word + " ")) {
                matched++;
            }
        }
        return counted == 0 ? 0.0 : (double) matched / counted;
    }

    static boolean matches(String query, String candidate) {
        return similarity(query, candidate) >= MIN_SIMILARITY;
    }

    /**
     * Lower-cased, accent-stripped, punctuation-free.
     *
     * <p>The accent strip is what makes {@code "Café Noma"} match
     * {@code "Cafe Noma"} — and it matters more here than it looks, because the
     * text on our side may have come through OCR or a caption where the accent
     * was lost or mangled.
     */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
