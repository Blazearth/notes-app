package com.weavr.extraction.ytdlp;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Primary YouTube metadata + transcript source that does NOT hit YouTube
 * directly, bypassing the datacenter-IP bot check. Ported unchanged from
 * {@code api}'s {@code RapidYtClient} (docs/extraction-architecture.md
 * Phase 3, F4/F6/F8 already landed in the monolith and carried over here).
 *
 * <p>Returns {@link Optional#empty()} on any failure so the caller can fall
 * back to yt-dlp silently.
 */
@Component
public class RapidYtClient {

    private static final Logger log = LoggerFactory.getLogger(RapidYtClient.class);

    // Matches youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID,
    // youtube.com/live/ID (also covers m.youtube.com and music.youtube.com,
    // since the match isn't anchored to the start of the string).
    private static final Pattern YT_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    /** 1 initial attempt + at most 2 retries. */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(200);
    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(2);

    /** A safety bound, not a measured typical count — RapidAPI's track list size is unverified. */
    private static final int MAX_CAPTION_TRACKS_TO_TRY = 5;

    private final RapidYtProperties props;
    private final RestClient http;
    private final RestClient captionHttp; // plain, no RapidAPI headers
    private final ObjectMapper objectMapper;

    RapidYtClient(RapidYtProperties props, @Qualifier("rapidYt") RestClient.Builder builder,
                 ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = builder.clone()
                .baseUrl(RapidYtProperties.BASE_URL)
                .defaultHeader("x-rapidapi-host", RapidYtProperties.RAPID_HOST)
                .defaultHeader("x-rapidapi-key", props.apiKey() != null ? props.apiKey() : "")
                .build();
        // Caption URLs are plain YouTube endpoints — no RapidAPI headers needed
        this.captionHttp = builder.clone().build();
    }

    /** @return empty if disabled, video not found, or every retry attempt failed */
    public Optional<ProbeResult> probe(String url) {
        if (!props.enabled()) return Optional.empty();

        String videoId = extractVideoId(url);
        if (videoId == null) return Optional.empty();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doProbe(videoId);
            } catch (Exception e) {
                boolean lastAttempt = attempt == MAX_ATTEMPTS;
                if (!isRetryable(e) || lastAttempt) {
                    log.warn("RapidAPI YouTube probe failed for {} (attempt {}/{}): {}",
                            videoId, attempt, MAX_ATTEMPTS, e.getMessage());
                    return Optional.empty();
                }
                log.debug("RapidAPI YouTube probe attempt {}/{} failed for {}, retrying: {}",
                        attempt, MAX_ATTEMPTS, videoId, e.getMessage());
                sleepWithJitter(attempt);
            }
        }
        return Optional.empty();
    }

    private Optional<ProbeResult> doProbe(String videoId) {
        String json = http.get()
                .uri("/dl?id={id}", videoId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new RapidApiStatusException(res.getStatusCode().value());
                })
                .body(String.class);

        if (json == null) return Optional.empty();

        JsonNode root = objectMapper.readTree(json);
        if (!"OK".equals(root.path("status").asText())) return Optional.empty();

        // --- metadata ---
        String title     = text(root, "title");
        String uploader  = text(root, "channelTitle");
        String desc      = text(root, "description");
        Double duration  = root.path("lengthSeconds").isNumber()
                ? root.path("lengthSeconds").asDouble() : null;
        String thumbnail = root.path("thumbnail").isArray() && root.path("thumbnail").size() > 0
                ? root.path("thumbnail").get(root.path("thumbnail").size() - 1).path("url").asText(null)
                : null;

        // --- caption tracks ---
        List<String> langCodes = new ArrayList<>();
        List<String> candidateUrls = new ArrayList<>();

        JsonNode tracks = root.path("captions").path("captionTracks");
        if (tracks.isArray()) {
            for (JsonNode track : tracks) {
                String lang = track.path("languageCode").asText(null);
                if (lang != null) langCodes.add(lang);
                String baseUrl = track.path("baseUrl").asText(null);
                if (baseUrl != null) candidateUrls.add(baseUrl);
            }
        }

        SourceMetadata metadata = new SourceMetadata(
                videoId, title, desc, uploader, duration, thumbnail,
                List.copyOf(langCodes), List.of(), null);

        // --- transcript ---
        Optional<String> transcript = bestTranscript(candidateUrls);

        return Optional.of(new ProbeResult(metadata, transcript));
    }

    /**
     * Tries up to {@link #MAX_CAPTION_TRACKS_TO_TRY} tracks and keeps whichever
     * parses to the longest prose, rather than whichever RapidAPI happened to
     * list first.
     */
    private Optional<String> bestTranscript(List<String> candidateUrls) {
        String best = null;
        for (String url : candidateUrls.stream().limit(MAX_CAPTION_TRACKS_TO_TRY).toList()) {
            Optional<String> text = fetchTranscript(url);
            if (text.isPresent() && (best == null || text.get().length() > best.length())) {
                best = text.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Fetches the timedtext XML and strips it to plain text.
     * YouTube's {@code /api/timedtext} endpoint is public — no auth needed.
     */
    private Optional<String> fetchTranscript(String captionUrl) {
        try {
            String xml = captionHttp.get()
                    .uri(captionUrl)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new RuntimeException("Caption fetch returned HTTP " + res.getStatusCode());
                    })
                    .body(String.class);

            if (xml == null || xml.isBlank()) return Optional.empty();

            String text = parseTimedText(xml);
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            log.debug("Caption fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses YouTube timedtext XML (both srv3 and ttml-ish formats):
     * {@code <p t="320" d="6240">text</p>} → plain text lines.
     */
    static String parseTimedText(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entity processing (security)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            NodeList paragraphs = doc.getElementsByTagName("p");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                String line = paragraphs.item(i).getTextContent().strip();
                // Skip music/sound annotations like "(gentle music)"
                if (!line.isBlank() && !line.startsWith("(") && !line.endsWith(")")) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(line);
                }
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.debug("Timedtext XML parse failed: {}", e.getMessage());
            return "";
        }
    }

    public static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m = YT_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    /**
     * A connection failure, a 429 or a 5xx is worth one more attempt; a 404 or
     * any other 4xx is not.
     */
    private static boolean isRetryable(Exception e) {
        if (e instanceof RapidApiStatusException status) {
            return status.retryable();
        }
        return e instanceof ResourceAccessException;
    }

    /**
     * Full jitter (sleep a random duration between zero and the capped
     * exponential delay) rather than a fixed backoff.
     */
    private static void sleepWithJitter(int attempt) {
        Duration exponential = RETRY_BASE_DELAY.multipliedBy(1L << (attempt - 1));
        Duration capped = exponential.compareTo(RETRY_MAX_DELAY) > 0 ? RETRY_MAX_DELAY : exponential;
        long jitterMillis = ThreadLocalRandom.current().nextLong(capped.toMillis() + 1);
        try {
            Thread.sleep(jitterMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Carries the HTTP status code {@code onStatus} would otherwise discard. */
    private static final class RapidApiStatusException extends RuntimeException {
        private final int status;

        RapidApiStatusException(int status) {
            super("RapidAPI returned HTTP " + status);
            this.status = status;
        }

        boolean retryable() {
            return status == 429 || status >= 500;
        }
    }

    /**
     * Bundles the probe result — metadata always present, transcript only when
     * a caption track was available and successfully fetched.
     */
    public record ProbeResult(SourceMetadata metadata, Optional<String> transcript) {}
}
