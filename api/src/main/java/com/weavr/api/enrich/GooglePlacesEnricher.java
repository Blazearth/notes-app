package com.weavr.api.enrich;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills in a {@code place} save from Google Places.
 *
 * <p>This is the enricher that turns a saved restaurant from a note into
 * something actionable. "Navigate" — the type-specific action the spec promises
 * for a place — needs coordinates, and no caption has ever contained a pair of
 * coordinates. Neither has any Reel contained the opening hours, which is the
 * other thing you actually want to know at the moment you open the save.
 *
 * <p>Uses the Places <b>API (New)</b> {@code :searchText} endpoint, which takes
 * a field mask: an unmasked request bills at the highest SKU and returns a
 * payload dominated by fields nothing here reads.
 */
@Component
class GooglePlacesEnricher implements Enricher {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesEnricher.class);

    private static final String SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";

    /**
     * Exactly the fields used below. Google bills Places by field mask tier, so
     * this list is a cost decision as much as a payload one — adding
     * {@code reviews} or {@code photos} here moves every request to Enterprise
     * pricing.
     */
    private static final String FIELD_MASK = String.join(",",
            "places.id",
            "places.displayName",
            "places.formattedAddress",
            "places.location",
            "places.rating",
            "places.userRatingCount",
            "places.priceLevel",
            "places.websiteUri",
            "places.nationalPhoneNumber",
            "places.regularOpeningHours.weekdayDescriptions",
            "places.googleMapsUri");

    /** Google's enum → the {@code $$}-style string the extractor already produces. */
    private static final Map<String, String> PRICE_LEVELS = Map.of(
            "PRICE_LEVEL_FREE", "free",
            "PRICE_LEVEL_INEXPENSIVE", "$",
            "PRICE_LEVEL_MODERATE", "$$",
            "PRICE_LEVEL_EXPENSIVE", "$$$",
            "PRICE_LEVEL_VERY_EXPENSIVE", "$$$$");

    private final RestClient http;
    private final EnrichmentProperties props;
    private final ObjectMapper objectMapper;

    GooglePlacesEnricher(EnrichmentProperties props, ObjectMapper objectMapper,
                         RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder.build();
    }

    @Override
    public String knowledgeType() {
        return "place";
    }

    @Override
    public Optional<Map<String, Object>> enrich(Map<String, Object> structuredData) {
        if (!props.placesConfigured()) {
            return Optional.empty();
        }
        // `name`, not `title` — the trap that broke saveTitle() on the app side
        // and search_tsv in V1. Worth naming every time it comes up.
        String name = Fields.text(structuredData, "name");
        if (name == null) {
            return Optional.empty();
        }

        // The area, when the extractor got one, is what separates the right
        // Noma from the eleven other places called Noma. Without it a text
        // search is biased by the caller's IP, which for a server is a data
        // centre — precisely the wrong place to search near.
        String area = Fields.text(structuredData, "address");
        String query = area == null ? name : name + ", " + area;

        JsonNode place = search(query, name);
        if (place == null) {
            log.debug("Google Places had no confident match for '{}'", query);
            return Optional.empty();
        }

        Map<String, Object> added = new LinkedHashMap<>();
        Fields.putIfPresent(added, "address", place.path("formattedAddress").asString(null));
        Fields.putIfPresent(added, "phone", place.path("nationalPhoneNumber").asString(null));
        Fields.putIfPresent(added, "website", place.path("websiteUri").asString(null));
        Fields.putIfPresent(added, "mapsUrl", place.path("googleMapsUri").asString(null));
        Fields.putIfPresent(added, "priceRange",
                PRICE_LEVELS.get(place.path("priceLevel").asString("")));

        double rating = place.path("rating").asDouble(0);
        if (rating > 0) {
            int count = place.path("userRatingCount").asInt(0);
            // The count is the difference between a real 4.6 and one person's
            // opinion, and it costs nothing to carry.
            added.put("rating", count > 0
                    ? "%.1f/5 (%d reviews)".formatted(rating, count)
                    : "%.1f/5".formatted(rating));
        }

        JsonNode location = place.path("location");
        if (location.has("latitude") && location.has("longitude")) {
            // A flat pair rather than a nested object: the app renders unknown
            // fields generically, and a nested map would come out as "[object
            // Object]" on a screen nobody has changed for it.
            added.put("latitude", location.path("latitude").asDouble());
            added.put("longitude", location.path("longitude").asDouble());
        }

        JsonNode hours = place.path("regularOpeningHours").path("weekdayDescriptions");
        if (hours.isArray() && !hours.isEmpty()) {
            java.util.List<String> lines = new java.util.ArrayList<>();
            hours.forEach(line -> lines.add(line.asString("")));
            added.put("openingHours", lines);
        }

        return added.isEmpty() ? Optional.empty() : Optional.of(added);
    }

    /**
     * Text search returns its best guess for anything, including a garbled OCR
     * line — so the top result is checked against the name we actually asked
     * for before any of it is believed. See {@link TitleMatch}.
     */
    private JsonNode search(String query, String name) {
        try {
            byte[] raw = http.post()
                    .uri(SEARCH_URL)
                    .header("X-Goog-Api-Key", props.googlePlacesApiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(Map.of(
                            "textQuery", query,
                            "maxResultCount", 5)))
                    .retrieve()
                    .body(byte[].class);
            if (raw == null) {
                return null;
            }
            JsonNode places = objectMapper.readTree(raw).path("places");
            for (JsonNode candidate : places) {
                String displayName = candidate.path("displayName").path("text").asString("");
                if (TitleMatch.matches(name, displayName)) {
                    return candidate;
                }
            }
            return null;
        } catch (RuntimeException e) {
            // Never fatal: the save is already ready and already searchable.
            log.warn("Google Places request failed for '{}': {}", query, e.toString());
            return null;
        }
    }
}
