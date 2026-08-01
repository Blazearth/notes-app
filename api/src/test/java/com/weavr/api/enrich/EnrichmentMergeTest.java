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
}
