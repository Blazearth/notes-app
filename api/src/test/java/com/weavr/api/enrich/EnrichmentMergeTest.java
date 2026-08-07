package com.weavr.api.enrich;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The additive-merge rule: enrichment fills gaps and never overwrites what the
 * user's own content said.
 *
 * <p>Both directions are failures worth catching. Overwriting turns a save of
 * "this pasta takes 20 minutes" into a database's 25 and silently contradicts
 * the thing the user actually watched. Refusing to fill an {@code [unclear]}
 * leaves enrichment doing nothing at all, since {@code [unclear]} is precisely
 * what the extractor writes when the content did not say.
 */
class EnrichmentMergeTest {

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }

    @Test
    void fillsAFieldTheContentNeverHad() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("title", "Oppenheimer"),
                map("director", "Christopher Nolan"));

        assertThat(additions).containsEntry("director", "Christopher Nolan");
    }

    /**
     * {@code [unclear]} is the registry's marker for "genuinely absent", not a
     * value. Treating it as one would make enrichment a no-op on every field it
     * exists to fill.
     */
    @Test
    void treatsTheUnclearSentinelAsAGap() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("director", "[unclear]", "year", "[unclear]"),
                map("director", "Christopher Nolan", "year", "2023"));

        assertThat(additions)
                .containsEntry("director", "Christopher Nolan")
                .containsEntry("year", "2023");
    }

    @Test
    void neverOverwritesWhatTheContentActuallySaid() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("rating", "8.5/10", "director", "Christopher Nolan"),
                map("rating", "7.9/10", "director", "Someone Else"));

        assertThat(additions)
                .as("the caption is what the user saw and chose to save")
                .isEmpty();
    }

    /**
     * The extractor emits {@code []} for "the content listed none", which is
     * absence rather than a claim that the film has no genres — so it is a gap.
     */
    @Test
    void anEmptyArrayIsAGap() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("genre", List.of()),
                map("genre", List.of("drama", "thriller")));

        assertThat(additions).containsEntry("genre", List.of("drama", "thriller"));
    }

    @Test
    void aNonEmptyArrayIsNotAGap() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("genre", List.of("thriller")),
                map("genre", List.of("drama", "historical")));

        assertThat(additions).isEmpty();
    }

    @Test
    void aBlankStringIsAGap() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("address", "   "),
                map("address", "Refshalevej 96, Copenhagen"));

        assertThat(additions).containsEntry("address", "Refshalevej 96, Copenhagen");
    }

    @Test
    void nullsFromAnEnricherAreDropped() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("director", "[unclear]"),
                map("director", null));

        assertThat(additions).isEmpty();
    }

    /**
     * {@link RecommendationListEnricher}'s whole reason for existing: an
     * object-array field (an existing, non-empty {@code items} list) can
     * still gain new per-item keys, without the enricher being able to touch
     * a field the creator's own content already produced.
     */
    @Test
    void perItemAdditionsMergeIntoAnObjectArrayWithoutTouchingExistingFields() {
        Map<String, Object> current = map("items", List.of(
                map("name", "Dune", "reason", "best sci-fi of the decade"),
                map("name", "Arrival", "reason", "quietly devastating")));
        Map<String, Object> found = map("items", List.of(
                map("posterUrl", "https://image.tmdb.org/dune.jpg"),
                map("posterUrl", "https://image.tmdb.org/arrival.jpg")));

        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(current, found);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) additions.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("name", "Dune")
                .as("the field a watchlist entry is worthless without must survive untouched")
                .containsEntry("reason", "best sci-fi of the decade")
                .containsEntry("posterUrl", "https://image.tmdb.org/dune.jpg");
        assertThat(items.get(1)).containsEntry("posterUrl", "https://image.tmdb.org/arrival.jpg");
    }

    /**
     * An enricher must never overwrite an item field the extraction already
     * filled — the same additive-only rule as the top level, applied per item.
     */
    @Test
    void perItemMergeNeverOverwritesAFieldTheItemAlreadyHas() {
        Map<String, Object> current = map("items", List.of(
                map("name", "Dune", "reason", "the real reason")));
        Map<String, Object> found = map("items", List.of(
                map("reason", "a different, wrong reason", "posterUrl", "https://image.tmdb.org/dune.jpg")));

        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(current, found);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) additions.get("items");
        assertThat(items.get(0))
                .containsEntry("reason", "the real reason")
                .containsEntry("posterUrl", "https://image.tmdb.org/dune.jpg");
    }

    /**
     * A length mismatch means the enricher did not describe the list
     * item-for-item — too risky to guess a correspondence, so nothing merges.
     */
    @Test
    void aLengthMismatchLeavesTheListUntouched() {
        Map<String, Object> current = map("items", List.of(
                map("name", "Dune"), map("name", "Arrival")));
        Map<String, Object> found = map("items", List.of(
                map("posterUrl", "https://image.tmdb.org/dune.jpg")));

        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(current, found);

        assertThat(additions).isEmpty();
    }

    /** Every item found nothing new — the merge must not report a no-op as an addition. */
    @Test
    void anObjectArrayWithNothingNewIsNotReportedAsAnAddition() {
        Map<String, Object> current = map("items", List.of(map("name", "Dune")));
        Map<String, Object> found = map("items", List.of(Map.of()));

        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(current, found);

        assertThat(additions).isEmpty();
    }

    /**
     * Plain string arrays (e.g. TMDB's `genre` against a movie's existing,
     * non-empty genre list) must fall through unchanged rather than being
     * treated as an object array — regression guard for the merge extension
     * added alongside {@link RecommendationListEnricher}.
     */
    @Test
    void aNonEmptyStringArrayOfTheSameLengthIsStillNotAGap() {
        Map<String, Object> additions = EnrichSaveHandler.gapsOnly(
                map("genre", List.of("thriller", "drama")),
                map("genre", List.of("drama", "historical")));

        assertThat(additions).isEmpty();
    }
}
