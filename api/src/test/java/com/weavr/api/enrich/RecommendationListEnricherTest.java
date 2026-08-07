package com.weavr.api.enrich;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mocked, same known gap as {@link TmdbEnricherTest}: what's pinned here is
 * the request shape and the per-item additive contract, not whether TMDB's
 * {@code /search/multi} actually answers this way.
 */
class RecommendationListEnricherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final EnrichmentProperties PROPS = new EnrichmentProperties(
            true, "tmdb-token", "places-key", Duration.ofSeconds(10));

    private MockRestServiceServer server;
    private RecommendationListEnricher enricher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        enricher = new RecommendationListEnricher(PROPS, MAPPER, builder);
    }

    private static byte[] multiResponse(Map<String, Object> result) {
        return MAPPER.writeValueAsBytes(Map.of("results", List.of(result)));
    }

    @Test
    void addsAPosterToAScreenKindItemThatMatches() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andRespond(withSuccess(multiResponse(Map.of(
                        "media_type", "movie",
                        "title", "Dune",
                        "release_date", "2021-10-21",
                        "poster_path", "/dune.jpg")), MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> found = enricher.enrich(Map.of(
                "items", List.of(Map.of("name", "Dune", "kind", "film", "year", "2021", "reason", "epic"))));

        assertThat(found).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) found.get().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("posterUrl", "https://image.tmdb.org/t/p/w500/dune.jpg");
    }

    /** Books, games and other non-screen kinds must never spend a TMDB request. */
    @Test
    void skipsItemsWhoseKindIsNotScreenMedia() {
        Optional<Map<String, Object>> found = enricher.enrich(Map.of(
                "items", List.of(Map.of("name", "Project Hail Mary", "kind", "book"))));

        assertThat(found).isEmpty();
        server.verify();
    }

    /** An unrelated result sharing no words with the query must not attach a wrong poster. */
    @Test
    void rejectsAResultThatIsNotTheThingAskedAbout() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andRespond(withSuccess(multiResponse(Map.of(
                        "media_type", "movie",
                        "title", "Completely Unrelated Bakery Documentary",
                        "release_date", "2019-01-01",
                        "poster_path", "/wrong.jpg")), MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> found = enricher.enrich(Map.of(
                "items", List.of(Map.of("name", "Dune", "kind", "film"))));

        assertThat(found).isEmpty();
    }

    /** Caps requests regardless of list length — TMDB is free but not latency-free. */
    @Test
    void capsLookupsAtTenItemsRegardlessOfListLength() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(10),
                        requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andRespond(withSuccess(multiResponse(Map.of(
                        "media_type", "movie",
                        "title", "Match",
                        "release_date", "2020-01-01",
                        "poster_path", "/p.jpg")), MediaType.APPLICATION_JSON));

        List<Object> items = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            items.add(Map.of("name", "Match", "kind", "film"));
        }

        Optional<Map<String, Object>> found = enricher.enrich(Map.of("items", items));

        assertThat(found).isPresent();
        server.verify();
    }

    @Test
    void anOutageIsSilentRatherThanFatal() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andRespond(withServerError());

        assertThat(enricher.enrich(Map.of(
                "items", List.of(Map.of("name", "Dune", "kind", "film"))))).isEmpty();
    }

    @Test
    void withoutAKeyItDoesNothing() {
        RecommendationListEnricher unconfigured = new RecommendationListEnricher(
                new EnrichmentProperties(true, "  ", null, Duration.ofSeconds(10)),
                MAPPER, RestClient.builder());

        assertThat(unconfigured.enrich(Map.of(
                "items", List.of(Map.of("name", "Dune", "kind", "film"))))).isEmpty();
    }

    @Test
    void anEmptyItemsListDoesNothing() {
        assertThat(enricher.enrich(Map.of("items", List.of()))).isEmpty();
        server.verify();
    }
}
