package com.weavr.api.enrich;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills in a {@code movie} save from TMDB.
 *
 * <p>A caption rarely says the year, the director, or what it is actually
 * about — it says "watched this, incredible". TMDB knows all three for free,
 * which is the point: the alternative is asking Gemini to recall them, which
 * costs a request from a 500-a-day pool <em>and</em> invites a confident
 * invention.
 *
 * <p>Two searches, not one: {@code /search/movie} then {@code /search/tv},
 * because the {@code movie} knowledge type covers both and TMDB does not.
 */
@Component
class TmdbEnricher implements Enricher {

    private static final Logger log = LoggerFactory.getLogger(TmdbEnricher.class);

    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

    private final RestClient http;
    private final EnrichmentProperties props;
    private final ObjectMapper objectMapper;

    TmdbEnricher(EnrichmentProperties props, ObjectMapper objectMapper,
                 RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder.build();
    }

    @Override
    public String knowledgeType() {
        return "movie";
    }

    @Override
    public Optional<Map<String, Object>> enrich(Map<String, Object> structuredData) {
        if (!props.tmdbConfigured()) {
            return Optional.empty();
        }
        String title = Fields.text(structuredData, "title");
        if (title == null) {
            return Optional.empty();
        }
        String year = Fields.text(structuredData, "year");

        JsonNode match = search("/search/movie", title, year, "title", "release_date");
        boolean isTv = false;
        if (match == null) {
            match = search("/search/tv", title, year, "name", "first_air_date");
            isTv = match != null;
        }
        if (match == null) {
            log.debug("TMDB had no confident match for '{}'", title);
            return Optional.empty();
        }

        Map<String, Object> added = new LinkedHashMap<>();
        int id = match.path("id").asInt();
        // K4: the canonical id collections merge on (Entities.key prefers
        // "tmdb:<id>" over the string key) — this is what resolves "Shingeki
        // no Kyojin" vs. "Attack on Titan" once TMDB has matched both to the
        // same title, which no normalization rule over the strings could do.
        added.put("tmdbId", String.valueOf(id));
        String date = match.path(isTv ? "first_air_date" : "release_date").asString("");
        if (date.length() >= 4) {
            added.put("year", date.substring(0, 4));
        }
        Fields.putIfPresent(added, "synopsis", match.path("overview").asString(null));
        double voteAverage = match.path("vote_average").asDouble(0);
        if (voteAverage > 0) {
            // Formatted the way the extractor's own examples do ("8.5/10"), so
            // the field reads the same however it was filled.
            added.put("rating", "%.1f/10".formatted(voteAverage));
        }
        String poster = match.path("poster_path").asString(null);
        if (poster != null && !poster.isBlank()) {
            added.put("posterUrl", IMAGE_BASE + poster);
        }

        // The credits call is what actually gets the director — the search
        // result never carries one. Worth the second request: "who made this"
        // is the single most common thing a film caption leaves out.
        Fields.putIfPresent(added, "director", director(id, isTv));
        List<String> genres = genres(match);
        if (!genres.isEmpty()) {
            added.put("genre", genres);
        }
        added.put("tmdbUrl", "https://www.themoviedb.org/" + (isTv ? "tv/" : "movie/") + id);

        return added.isEmpty() ? Optional.empty() : Optional.of(added);
    }

    /**
     * Returns the first result whose title actually matches, not simply the
     * first result — see {@link TitleMatch}. When the save carries a year, a
     * result from a different year is rejected outright: remakes share a title
     * and are otherwise indistinguishable to a name comparison.
     */
    private JsonNode search(String path, String title, String year, String titleField, String dateField) {
        JsonNode results = get(TMDB_BASE + path + "?query="
                + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&include_adult=false");
        if (results == null) {
            return null;
        }
        for (JsonNode candidate : results.path("results")) {
            if (!TitleMatch.matches(title, candidate.path(titleField).asString(""))) {
                continue;
            }
            String candidateDate = candidate.path(dateField).asString("");
            if (year != null && candidateDate.length() >= 4 && !year.equals(candidateDate.substring(0, 4))) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private String director(int id, boolean isTv) {
        JsonNode credits = get(TMDB_BASE + (isTv ? "/tv/" : "/movie/") + id + "/credits");
        if (credits == null) {
            return null;
        }
        for (JsonNode member : credits.path("crew")) {
            if ("Director".equals(member.path("job").asString(""))) {
                return member.path("name").asString(null);
            }
        }
        // A series has creators rather than one director, and its per-episode
        // crew is not what anyone means by "who made this" — better to leave
        // the field as the extractor found it than to fill it with an episode
        // director nobody has heard of.
        return null;
    }

    private List<String> genres(JsonNode match) {
        List<String> names = new ArrayList<>();
        JsonNode ids = match.path("genre_ids");
        for (JsonNode id : ids) {
            String name = TmdbGenres.name(id.asInt());
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Every failure here returns null rather than throwing. Enrichment runs
     * against a save that is already {@code ready} and already findable —
     * turning a TMDB outage into a failed save would trade a complete result
     * for nothing.
     */
    private JsonNode get(String url) {
        try {
            byte[] raw = http.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + props.tmdbApiKey())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(byte[].class);
            // Bytes, not String, for the reason the Gemini client learned the
            // hard way: JSON is UTF-8 by spec, and a response without a charset
            // on its Content-Type lets Spring guess. Film titles are exactly
            // the text where that shows up — "Amélie", "Das Boot".
            return raw == null ? null : objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("TMDB request failed ({}): {}", url, e.toString());
            return null;
        }
    }
}
