package com.weavr.api.enrich;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Binds {@link MockRestServiceServer} to the injected builder, the pattern the
 * Gemini encoding bug forced on every HTTP client here.
 *
 * <p><b>These are mocked, and that is a known gap, not a claim.</b> The same
 * shape of test was green while a real yt-dlp had three defects and a real
 * Gemini call had a UTF-8 bug. What is pinned here is the request shape and the
 * merge behaviour; whether TMDB actually answers this way is unverified until
 * a real key runs against it.
 */
class TmdbEnricherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final EnrichmentProperties PROPS = new EnrichmentProperties(
            true, "tmdb-token", "places-key", null, Duration.ofSeconds(10));

    private MockRestServiceServer server;
    private TmdbEnricher enricher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        enricher = new TmdbEnricher(PROPS, MAPPER, builder);
    }

    private static byte[] searchResponse(String title, String date, Object... extras) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of(
                "id", 872585,
                "title", title,
                "release_date", date,
                "overview", "The story of J. Robert Oppenheimer.",
                "vote_average", 8.1,
                "poster_path", "/abc.jpg",
                "genre_ids", List.of(18, 36, 53)));
        for (int i = 0; i < extras.length; i += 2) {
            result.put(String.valueOf(extras[i]), extras[i + 1]);
        }
        return MAPPER.writeValueAsBytes(Map.of("results", List.of(result)));
    }

    private static byte[] creditsResponse(String director) {
        return MAPPER.writeValueAsBytes(Map.of("crew", List.of(
                Map.of("job", "Producer", "name", "Emma Thomas"),
                Map.of("job", "Director", "name", director))));
    }

    @Test
    void fillsYearDirectorGenreAndSynopsisFromTmdb() {
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tmdb-token"))
                .andRespond(withSuccess(searchResponse("Oppenheimer", "2023-07-21"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/movie/872585/credits"))
                .andRespond(withSuccess(creditsResponse("Christopher Nolan"),
                        MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> found = enricher.enrich(Map.of("title", "Oppenheimer"));

        assertThat(found).isPresent();
        assertThat(found.get())
                .containsEntry("year", "2023")
                .containsEntry("director", "Christopher Nolan")
                .containsEntry("rating", "8.1/10")
                .containsEntry("genre", List.of("drama", "history", "thriller"))
                .containsEntry("posterUrl", "https://image.tmdb.org/t/p/w500/abc.jpg")
                .containsEntry("tmdbUrl", "https://www.themoviedb.org/movie/872585")
                // K4: the canonical id collections merge on — Entities.key
                // prefers "tmdb:<id>" over the string key once this lands.
                .containsEntry("tmdbId", "872585");
        server.verify();
    }

    /**
     * The guard that matters. TMDB answers a garbled title with *something* —
     * accepting it would write a confident, wrong director onto a save, and
     * enrichment carries more authority than extraction because it looks like
     * it came from a database.
     */
    @Test
    void rejectsAResultThatIsNotTheFilmWeAskedAbout() {
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie")))
                .andRespond(withSuccess(searchResponse("Openheimer Bakery Documentary", "2019-01-01"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/tv")))
                .andRespond(withSuccess(MAPPER.writeValueAsBytes(Map.of("results", List.of())),
                        MediaType.APPLICATION_JSON));

        assertThat(enricher.enrich(Map.of("title", "Oppenheimer"))).isEmpty();
        server.verify();
    }

    /**
     * Remakes share a title and are otherwise indistinguishable to a name
     * comparison, so a year the save already carries is a hard filter.
     */
    @Test
    void aYearMismatchRejectsTheMatch() {
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie")))
                .andRespond(withSuccess(searchResponse("Dune", "1984-12-14"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/tv")))
                .andRespond(withSuccess(MAPPER.writeValueAsBytes(Map.of("results", List.of())),
                        MediaType.APPLICATION_JSON));

        assertThat(enricher.enrich(Map.of("title", "Dune", "year", "2021"))).isEmpty();
    }

    /** The {@code movie} type covers TV, and TMDB does not — hence the second search. */
    @Test
    void fallsBackToTheTvSearchWhenTheMovieSearchMisses() {
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie")))
                .andRespond(withSuccess(MAPPER.writeValueAsBytes(Map.of("results", List.of())),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/search/tv")))
                .andRespond(withSuccess(MAPPER.writeValueAsBytes(Map.of("results", List.of(Map.of(
                        "id", 136315,
                        "name", "The Bear",
                        "first_air_date", "2022-06-23",
                        "overview", "A chef returns to run his family sandwich shop.",
                        "vote_average", 8.4,
                        "genre_ids", List.of(35, 18))))), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.themoviedb.org/3/tv/136315/credits"))
                .andRespond(withSuccess(MAPPER.writeValueAsBytes(Map.of("crew", List.of())),
                        MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> found = enricher.enrich(Map.of("title", "The Bear"));

        assertThat(found).isPresent();
        assertThat(found.get())
                .containsEntry("year", "2022")
                .containsEntry("tmdbUrl", "https://www.themoviedb.org/tv/136315")
                .containsEntry("tmdbId", "136315")
                .as("a series has creators, not one director — better empty than an episode director")
                .doesNotContainKey("director");
    }

    /**
     * Enrichment happens to a save that is already {@code ready} and already
     * searchable. Turning a TMDB outage into a failed save would trade a
     * complete result for nothing.
     */
    @Test
    void anOutageIsSilentRatherThanFatal() {
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie")))
                .andRespond(withServerError());
        server.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/tv")))
                .andRespond(withServerError());

        assertThat(enricher.enrich(Map.of("title", "Oppenheimer"))).isEmpty();
    }

    /** No key configured means no request at all, not a request that 401s. */
    @Test
    void withoutAKeyItDoesNothing() {
        TmdbEnricher unconfigured = new TmdbEnricher(
                new EnrichmentProperties(true, "  ", null, null, Duration.ofSeconds(10)),
                MAPPER, RestClient.builder());

        assertThat(unconfigured.enrich(Map.of("title", "Oppenheimer"))).isEmpty();
    }

    /**
     * {@code [unclear]} is a sentinel, not a title. Sending it as a search query
     * is both a wasted request and a good way to match something absurd.
     */
    @Test
    void doesNotSearchForTheUnclearSentinel() {
        assertThat(enricher.enrich(Map.of("title", "[unclear]"))).isEmpty();
        server.verify();
    }
}
