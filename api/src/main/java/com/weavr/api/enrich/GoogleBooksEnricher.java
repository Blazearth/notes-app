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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills gaps in a {@code book} save from the Google Books API.
 *
 * <p>A caption rarely carries the author, publisher or page count — it says
 * "reading this, fascinating". Google Books knows all three for free, which is
 * the point: asking Gemini to recall them costs a request from a 500-a-day pool
 * <em>and</em> invites a confident invention (wrong ISBNs, hallucinated authors).
 *
 * <p>Only the first matching volume is used, and it is checked against
 * {@link TitleMatch} before being trusted — the same guard TMDB uses for the
 * same reason: Books will return a result for almost any query, and taking the
 * top hit on faith would write the wrong author onto a save.
 *
 * <p>Fields filled, in priority order (all additive — nothing already extracted
 * is overwritten):
 * <ul>
 *   <li>{@code author} — first listed author</li>
 *   <li>{@code publisher} — publisher name</li>
 *   <li>{@code pageCount} — total pages as a string, e.g. {@code "320"}</li>
 *   <li>{@code genre} — Google's subject categories, trimmed to 4</li>
 *   <li>{@code summary} — editorial description (stripped of HTML)</li>
 *   <li>{@code thumbnailUrl} — cover image URL (HTTPS-upgraded)</li>
 *   <li>{@code isbn} — ISBN-13 when available, ISBN-10 otherwise</li>
 *   <li>{@code booksUrl} — canonical Google Books preview link</li>
 * </ul>
 */
@Component
class GoogleBooksEnricher implements Enricher {

    private static final Logger log = LoggerFactory.getLogger(GoogleBooksEnricher.class);

    private static final String BOOKS_BASE = "https://www.googleapis.com/books/v1";

    /** Maximum subject categories to surface — beyond 4 they become noisy tags. */
    private static final int MAX_GENRES = 4;

    private final RestClient http;
    private final EnrichmentProperties props;
    private final ObjectMapper objectMapper;

    GoogleBooksEnricher(EnrichmentProperties props, ObjectMapper objectMapper,
                        @Qualifier("enrich") RestClient.Builder restClientBuilder) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = restClientBuilder.build();
    }

    @Override
    public String knowledgeType() {
        return "book";
    }

    @Override
    public Optional<Map<String, Object>> enrich(Map<String, Object> structuredData) {
        if (!props.booksConfigured()) {
            return Optional.empty();
        }
        String title = Fields.text(structuredData, "title");
        if (title == null) {
            return Optional.empty();
        }
        // Narrow the query when an author is already known — reduces ambiguity
        // for common titles like "Dune" or "It" that map to many volumes.
        String author = Fields.text(structuredData, "author");

        JsonNode volume = search(title, author);
        if (volume == null) {
            log.debug("Google Books had no confident match for '{}'", title);
            return Optional.empty();
        }

        JsonNode info = volume.path("volumeInfo");
        Map<String, Object> added = new LinkedHashMap<>();

        // Author — first entry in the authors array.
        JsonNode authorsNode = info.path("authors");
        if (authorsNode.isArray() && !authorsNode.isEmpty()) {
            Fields.putIfPresent(added, "author", authorsNode.get(0).asText(null));
        }

        Fields.putIfPresent(added, "publisher", info.path("publisher").asText(null));

        int pages = info.path("pageCount").asInt(0);
        if (pages > 0) {
            added.put("pageCount", String.valueOf(pages));
        }

        // Genres — Books uses "categories" (subject headings). Strip duplicates
        // and cap at MAX_GENRES so the card doesn't become a tag cloud.
        List<String> genres = categories(info);
        if (!genres.isEmpty()) {
            added.put("genre", genres);
        }

        // Summary — the Books API embeds light HTML (<br>, <b>, <i>). Strip tags
        // rather than surfacing raw markup to the client.
        String description = info.path("description").asText(null);
        if (description == null) {
            description = info.path("subtitle").asText(null);
        }
        if (description != null && !description.isBlank()) {
            Fields.putIfPresent(added, "summary", stripHtml(description));
        }

        // Thumbnail — upgrade to HTTPS; Books returns http:// links.
        String thumbnail = info.path("imageLinks").path("thumbnail").asText(null);
        if (thumbnail != null && !thumbnail.isBlank()) {
            added.put("thumbnailUrl", thumbnail.replace("http://", "https://"));
        }

        // ISBN — prefer ISBN-13 over ISBN-10 for uniqueness.
        String isbn = isbn(info);
        Fields.putIfPresent(added, "isbn", isbn);

        // Canonical link to the Books preview page.
        Fields.putIfPresent(added, "booksUrl", info.path("canonicalVolumeLink").asText(null));

        return added.isEmpty() ? Optional.empty() : Optional.of(added);
    }

    /**
     * Returns the first volume whose title passes {@link TitleMatch}.
     *
     * <p>When an author is known, the query is narrowed with {@code +inauthor:}
     * — Books' own filter, not a post-search step — which matters most for
     * common titles where the first hit would otherwise be a different book.
     */
    private JsonNode search(String title, String author) {
        StringBuilder query = new StringBuilder();
        query.append("intitle:").append(URLEncoder.encode(title, StandardCharsets.UTF_8));
        if (author != null) {
            query.append("+inauthor:").append(URLEncoder.encode(author, StandardCharsets.UTF_8));
        }

        String url = BOOKS_BASE + "/volumes?q=" + query
                + "&maxResults=5"
                + "&printType=books"
                + "&key=" + props.googleBooksApiKey();

        JsonNode response = get(url);
        if (response == null) {
            return null;
        }

        JsonNode items = response.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }

        for (JsonNode item : items) {
            String candidateTitle = item.path("volumeInfo").path("title").asText("");
            if (TitleMatch.matches(title, candidateTitle)) {
                return item;
            }
        }
        return null;
    }

    /** Picks ISBN-13 first, then ISBN-10. */
    private static String isbn(JsonNode info) {
        JsonNode identifiers = info.path("industryIdentifiers");
        if (!identifiers.isArray()) {
            return null;
        }
        String isbn10 = null;
        for (JsonNode id : identifiers) {
            String type = id.path("type").asText("");
            String identifier = id.path("identifier").asText(null);
            if ("ISBN_13".equals(type)) {
                return identifier;          // prefer 13, return immediately
            }
            if ("ISBN_10".equals(type)) {
                isbn10 = identifier;
            }
        }
        return isbn10;
    }

    /**
     * Subject categories, deduplicated and capped.
     *
     * <p>Books often returns broad headings like "Fiction / Science Fiction /
     * Space Opera" — we split on "/" to get the leaf terms, normalise case,
     * and deduplicate before capping so the result contains real genre words,
     * not path segments.
     */
    private static List<String> categories(JsonNode info) {
        JsonNode cats = info.path("categories");
        if (!cats.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode cat : cats) {
            for (String part : cat.asText("").split("/")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank() && !result.contains(trimmed)) {
                    result.add(trimmed);
                }
                if (result.size() >= MAX_GENRES) {
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * Minimal HTML stripper — removes tags and collapses whitespace.
     *
     * <p>Full-featured HTML parsing is not worth the dependency for editorial
     * blurbs: the only tags Books uses here are {@code <br>}, {@code <b>},
     * {@code <i>}, and {@code <p>}.
     */
    private static String stripHtml(String html) {
        return html
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * Every failure returns null rather than throwing. Enrichment runs against
     * a save that is already {@code ready} and already findable — a Books
     * outage must not fail the save.
     */
    private JsonNode get(String url) {
        try {
            byte[] raw = http.get()
                    .uri(url)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(byte[].class);
            // Bytes rather than String for the same reason TmdbEnricher uses
            // them: book titles are exactly where UTF-8 vs charset-guessing
            // matters ("Kafka on the Shore", "Les Misérables").
            return raw == null ? null : objectMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("Google Books request failed ({}): {}", url, e.toString());
            return null;
        }
    }
}
