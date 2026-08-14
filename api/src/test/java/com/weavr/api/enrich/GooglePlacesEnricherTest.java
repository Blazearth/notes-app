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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mocked, like every other HTTP client here — see {@link TmdbEnricherTest} for
 * why that is stated as a gap rather than passed over.
 */
class GooglePlacesEnricherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final EnrichmentProperties PROPS = new EnrichmentProperties(
            true, "tmdb-token", "places-key", null, Duration.ofSeconds(10));

    private MockRestServiceServer server;
    private GooglePlacesEnricher enricher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        enricher = new GooglePlacesEnricher(PROPS, MAPPER, builder);
    }

    private static byte[] placesResponse(String displayName) {
        // Map.ofEntries rather than Map.of: the latter tops out at ten pairs,
        // and a realistic Places result has more than that.
        return MAPPER.writeValueAsBytes(Map.of("places", List.of(Map.ofEntries(
                Map.entry("id", "ChIJabc"),
                Map.entry("displayName", Map.of("text", displayName)),
                Map.entry("formattedAddress", "Refshalevej 96, 1432 København, Denmark"),
                Map.entry("location", Map.of("latitude", 55.6828, "longitude", 12.6103)),
                Map.entry("rating", 4.6),
                Map.entry("userRatingCount", 2841),
                Map.entry("priceLevel", "PRICE_LEVEL_VERY_EXPENSIVE"),
                Map.entry("websiteUri", "https://noma.dk"),
                Map.entry("nationalPhoneNumber", "32 96 32 97"),
                Map.entry("googleMapsUri", "https://maps.google.com/?cid=123"),
                Map.entry("regularOpeningHours", Map.of("weekdayDescriptions",
                        List.of("Monday: Closed", "Tuesday: 5:00 – 11:00 PM")))))));
    }

    @Test
    void fillsAddressCoordinatesHoursAndRating() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "places-key"))
                // The field mask is a cost decision, not just a payload one:
                // an unmasked request bills at the highest SKU.
                .andExpect(header("X-Goog-FieldMask", containsString("places.location")))
                .andExpect(jsonPath("$.textQuery").value("Noma, Copenhagen"))
                .andRespond(withSuccess(placesResponse("Noma"), MediaType.APPLICATION_JSON));

        Optional<Map<String, Object>> found = enricher.enrich(Map.of(
                "name", "Noma", "address", "Copenhagen"));

        assertThat(found).isPresent();
        assertThat(found.get())
                .containsEntry("address", "Refshalevej 96, 1432 København, Denmark")
                .containsEntry("latitude", 55.6828)
                .containsEntry("longitude", 12.6103)
                .containsEntry("rating", "4.6/5 (2841 reviews)")
                .containsEntry("priceRange", "$$$$")
                .containsEntry("website", "https://noma.dk")
                .containsEntry("mapsUrl", "https://maps.google.com/?cid=123")
                .containsEntry("openingHours", List.of("Monday: Closed", "Tuesday: 5:00 – 11:00 PM"));
        server.verify();
    }

    /**
     * The area is what separates the right Noma from the eleven others.
     * Without it a text search is biased by the caller's IP, which for a server
     * is a data centre — precisely the wrong place to search near.
     */
    @Test
    void searchesByNameAloneWhenTheSaveHasNoArea() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(jsonPath("$.textQuery").value("Noma"))
                .andRespond(withSuccess(placesResponse("Noma"), MediaType.APPLICATION_JSON));

        assertThat(enricher.enrich(Map.of("name", "Noma", "address", "[unclear]"))).isPresent();
    }

    /**
     * The reason {@link TitleMatch} exists: Places answers "Noma" with a coffee
     * roaster rather than nothing, and its address would otherwise be written
     * onto a save about a Copenhagen restaurant.
     */
    @Test
    void rejectsAPlaceThatIsNotTheOneWeAskedAbout() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andRespond(withSuccess(placesResponse("Nomad Coffee Roasters"),
                        MediaType.APPLICATION_JSON));

        assertThat(enricher.enrich(Map.of("name", "Noma"))).isEmpty();
    }

    /** {@code name}, not {@code title} — the trap that broke saveTitle() and V1's search vector. */
    @Test
    void readsTheNameFieldNotTitle() {
        assertThat(enricher.enrich(Map.of("title", "Noma"))).isEmpty();
        server.verify();
    }

    @Test
    void anOutageIsSilentRatherThanFatal() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andRespond(withServerError());

        assertThat(enricher.enrich(Map.of("name", "Noma"))).isEmpty();
    }

    @Test
    void withoutAKeyItDoesNothing() {
        GooglePlacesEnricher unconfigured = new GooglePlacesEnricher(
                new EnrichmentProperties(true, "tmdb", null, null, Duration.ofSeconds(10)),
                MAPPER, RestClient.builder());

        assertThat(unconfigured.enrich(Map.of("name", "Noma"))).isEmpty();
    }

    @Test
    void theEnabledFlagTurnsEverythingOff() {
        GooglePlacesEnricher disabled = new GooglePlacesEnricher(
                new EnrichmentProperties(false, "tmdb", "places-key", null, Duration.ofSeconds(10)),
                MAPPER, RestClient.builder());

        assertThat(disabled.enrich(Map.of("name", "Noma"))).isEmpty();
    }
}
