package com.weavr.api.pipeline.ytdlp;

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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * directly, bypassing the datacenter-IP bot check that kills yt-dlp on Render.
 *
 * <p>Two HTTP calls per YouTube video in the common case, more when the metadata
 * probe needs a retry or several caption tracks have to be tried:
 * <ol>
 *   <li>RapidAPI {@code /dl?id=<videoId>} → metadata JSON + caption track URLs.
 *       Bounded retry with full jitter on a connection failure, a 429 or a 5xx
 *       (F4, docs/extraction-architecture.md) — a 404-shaped "not found" is not
 *       retried, since trying again will not make a deleted video reappear.</li>
 *   <li>Up to {@value #MAX_CAPTION_TRACKS_TO_TRY} caption {@code baseUrl}s →
 *       timestamped XML transcripts, keeping whichever parses to the longest
 *       prose (F6) — RapidAPI's response carries no manual-vs-auto flag to rank
 *       by, and "first track listed" is not a ranking, mirroring the same
 *       auto-generated-tracks-are-bigger-but-carry-less-text lesson
 *       {@code YtDlpClient.bestCaptions} already learned from yt-dlp's own
 *       captions.</li>
 * </ol>
 *
 * <p>The transcript is attached to the returned {@link SourceMetadata} via the
 * {@code description} field so the cascade's existing {@code combine()} helper
 * picks it up. {@code captionLanguages} is populated so
 * {@link SourceMetadata#hasCaptions()} returns {@code true}, which causes
 * {@link com.weavr.api.pipeline.ExtractionCascade} to take the captions branch
 * rather than fall through to ASR.
 *
 * <p>Returns {@link Optional#empty()} on any failure so the cascade can fall
 * back to yt-dlp silently.
 */
@Component
public class RapidYtClient {

    private static final Logger log = LoggerFactory.getLogger(RapidYtClient.class);

    // Matches youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID,
    // youtube.com/live/ID (also covers m.youtube.com and music.youtube.com,
    // since the match isn't anchored to the start of the string — F8).
    private static final Pattern YT_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    /** 1 initial attempt + at most 2 retries, matching the doc's own retry bound for this call. */
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

        // RapidAPI's /dl response carries no comment data — pinnedComment stays
        // null here. A YouTube save only gets one when this probe fails and the
        // cascade falls through to the plain yt-dlp path (below), or on Render
        // where yt-dlp itself is bot-blocked for YouTube, not at all yet.
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
     * list first (F6) — the same "rank by parsed output, not by which file is
     * listed or sized first" rule {@code YtDlpClient.bestCaptions} applies to
     * yt-dlp's own subtitle files, and for the identical reason: nothing here
     * tells us which track is manually authored versus auto-generated, so size
     * or position are not a proxy for quality.
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
     * Parses YouTube timedtext XML. Two real, distinct formats come back from
     * the same {@code /api/timedtext} endpoint depending on the query string,
     * and RapidAPI's {@code baseUrl} carries neither an {@code fmt=srv3} nor
     * any other {@code fmt} parameter — confirmed by fetching a real baseUrl
     * from a real RapidAPI response: it comes back as 24 {@code <text start=
     * "..." dur="...">} elements, zero {@code <p>} elements. The original
     * parser only ever collected {@code <p>}, so it silently returned an
     * empty string for every RapidAPI transcript fetch since this client was
     * written — a real, narrated, on-topic transcript existed and was thrown
     * away on every single request, not a RapidAPI limitation. Both tag
     * names are collected here; a real document only ever populates one.
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

            NodeList cues = doc.getElementsByTagName("p");
            if (cues.getLength() == 0) {
                cues = doc.getElementsByTagName("text");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cues.getLength(); i++) {
                String line = cues.item(i).getTextContent().strip();
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
     * any other 4xx is not (F4) — retrying will not make RapidAPI find a video
     * that does not exist, and a malformed/unparseable response is equally not
     * worth retrying, which is why this only matches these two specific cases
     * rather than defaulting every exception to retryable.
     */
    private static boolean isRetryable(Exception e) {
        if (e instanceof RapidApiStatusException status) {
            return status.retryable();
        }
        return e instanceof ResourceAccessException;
    }

    /**
     * Full jitter (sleep a random duration between zero and the capped
     * exponential delay) rather than a fixed backoff — avoids every retrying
     * caller waking up at the same instant and re-hitting RapidAPI in lockstep.
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

    /** Carries the HTTP status code {@code onStatus} would otherwise discard, so F4 can act on it. */
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
