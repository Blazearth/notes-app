package com.weavr.api.enrich;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds a poster image to the screen/TV entries of a {@code recommendation_list}
 * — Phase 5 §5.3: "possible but N lookups per save where every other type does
 * one," so this is deliberately narrower than {@link TmdbEnricher}.
 *
 * <h2>Why this is per-item, not top-level</h2>
 * <p>Every other enricher fills gaps in a save's flat fields. This one reaches
 * into {@code items[]} and adds to individual entries — {@link EnrichSaveHandler#gapsOnly}
 * merges it back per item (identity by array index, same precondition Phase
 * 4's item-state mechanism relies on), so a wrong or missing match on item 7
 * can only ever add a field to item 7. It can never touch {@code reason},
 * {@code name}, or any other field the creator's own content produced —
 * {@code reason} in particular is, per the registry's own comment on this
 * type, "the field to fight for," and enrichment must not be the thing that
 * risks it.
 *
 * <h2>Bounded, deliberately</h2>
 * <p>{@code MAX_LOOKUPS} caps the number of TMDB calls a single save can cost,
 * regardless of how long the list is — TMDB is free and not RPD-gated, so the
 * cost this bounds is latency and error surface, per the design doc, not
 * budget. Only items whose {@code kind} reads as film/TV are looked up at
 * all: a books-and-games list would otherwise spend its whole budget on
 * misses against a movie database.
 */
@Component
class RecommendationListEnricher implements Enricher {

    private static final Logger log = LoggerFactory.getLogger(RecommendationListEnricher.class);

    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

    /** Per the design doc: "cap at the first ~10 items." */
    private static final int MAX_LOOKUPS = 10;

    /** {@code kind} values worth asking a film/TV database about at all. */
    private static final Set<String> SCREEN_KINDS =
            Set.of("film", "movie", "tv", "series", "show", "anime");

    private final RestClient http;
    private final EnrichmentProperties props;
    private final ObjectMapper objectMapper;

    RecommendationListEnricher(EnrichmentProperties props, ObjectMapper objectMapper,
                               RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder.build();
    }

    @Override
    public String knowledgeType() {
        return "recommendation_list";
    }

    /**
     * @return {@code Map.of("items", perItemAdditions)} where {@code
     *         perItemAdditions} is the same length as the save's own {@code
     *         items} and each entry holds only the fields found for that
     *         position (often empty) — the shape {@link EnrichSaveHandler}'s
     *         per-item merge expects.
     */
    @Override
    public Optional<Map<String, Object>> enrich(Map<String, Object> structuredData) {
        if (!props.tmdbConfigured()) {
            return Optional.empty();
        }
        Object rawItems = structuredData.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
            return Optional.empty();
        }

        List<Object> perItemAdditions = new ArrayList<>(items.size());
        int lookups = 0;
        boolean anyFound = false;

        for (Object raw : items) {
            Map<String, Object> addition = Map.of();
            if (raw instanceof Map<?, ?> rawMap && lookups < MAX_LOOKUPS) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) rawMap;
                String name = Fields.text(item, "name");
                String kind = Fields.text(item, "kind");
                if (name != null && kind != null && SCREEN_KINDS.contains(kind.toLowerCase(Locale.ROOT))) {
                    lookups++;
                    addition = lookupPoster(name, Fields.text(item, "year"));
                    if (!addition.isEmpty()) {
                        anyFound = true;
                    }
                }
            }
            perItemAdditions.add(addition);
        }

        return anyFound ? Optional.of(Map.of("items", perItemAdditions)) : Optional.empty();
    }

    /**
     * {@code /search/multi} rather than {@link TmdbEnricher}'s two-search
     * movie/TV split — this only needs a poster, not a director or genres, so
     * one call per item is enough. {@link TitleMatch} guards it the same way:
     * a garbled or wrong-medium title must not attach a confident, wrong
     * poster to someone else's recommendation.
     */
    private Map<String, Object> lookupPoster(String name, String year) {
        JsonNode results = get(TMDB_BASE + "/search/multi?query="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&include_adult=false");
        if (results == null) {
            return Map.of();
        }
        for (JsonNode candidate : results.path("results")) {
            String mediaType = candidate.path("media_type").asString("");
            boolean isMovie = "movie".equals(mediaType);
            if (!isMovie && !"tv".equals(mediaType)) {
                continue;
            }
            String title = candidate.path(isMovie ? "title" : "name").asString("");
            if (!TitleMatch.matches(name, title)) {
                continue;
            }
            String date = candidate.path(isMovie ? "release_date" : "first_air_date").asString("");
            if (year != null && date.length() >= 4 && !year.equals(date.substring(0, 4))) {
                continue;
            }
            Map<String, Object> addition = new LinkedHashMap<>();
            // K4: the canonical id collections merge on — see TmdbEnricher's
            // matching comment. Written even when there is no poster, since
            // the id resolves aliases regardless of image availability.
            addition.put("tmdbId", String.valueOf(candidate.path("id").asInt()));
            String poster = candidate.path("poster_path").asString(null);
            if (poster != null && !poster.isBlank()) {
                addition.put("posterUrl", IMAGE_BASE + poster);
            }
            return addition;
        }
        return Map.of();
    }

    /** Every failure returns null — see {@link TmdbEnricher#get} for why. */
    private JsonNode get(String url) {
        try {
            byte[] raw = http.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + props.tmdbApiKey())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(byte[].class);
            return raw == null ? null : objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("TMDB multi-search failed ({}): {}", url, e.toString());
            return null;
        }
    }
}
