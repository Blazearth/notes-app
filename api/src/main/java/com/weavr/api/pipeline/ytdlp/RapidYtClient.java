package com.weavr.api.pipeline.ytdlp;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
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
 * <p>Two HTTP calls per YouTube video:
 * <ol>
 *   <li>RapidAPI {@code /dl?id=<videoId>} → metadata JSON + caption track URLs</li>
 *   <li>First available caption {@code baseUrl} → timestamped XML transcript</li>
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

    // Matches youtube.com/watch?v=ID, youtu.be/ID, youtube.com/shorts/ID
    private static final Pattern YT_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private final RapidYtProperties props;
    private final RestClient http;
    private final RestClient captionHttp; // plain, no RapidAPI headers
    private final ObjectMapper objectMapper;

    RapidYtClient(RapidYtProperties props, RestClient.Builder builder, ObjectMapper objectMapper) {
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

    /** @return empty if disabled, video not found, or any network/parse error */
    public Optional<ProbeResult> probe(String url) {
        if (!props.enabled()) return Optional.empty();

        String videoId = extractVideoId(url);
        if (videoId == null) return Optional.empty();

        try {
            return doProbe(videoId);
        } catch (Exception e) {
            log.warn("RapidAPI YouTube probe failed for {}: {}", videoId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ProbeResult> doProbe(String videoId) {
        String json = http.get()
                .uri("/dl?id={id}", videoId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new RuntimeException("RapidAPI returned HTTP " + res.getStatusCode());
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
        String firstCaptionUrl = null;

        JsonNode tracks = root.path("captions").path("captionTracks");
        if (tracks.isArray()) {
            for (JsonNode track : tracks) {
                String lang = track.path("languageCode").asText(null);
                if (lang != null) langCodes.add(lang);
                if (firstCaptionUrl == null) {
                    firstCaptionUrl = track.path("baseUrl").asText(null);
                }
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
        Optional<String> transcript = Optional.empty();
        if (firstCaptionUrl != null) {
            transcript = fetchTranscript(firstCaptionUrl);
        }

        return Optional.of(new ProbeResult(metadata, transcript));
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

            // Collect all <p> element text content
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
     * Bundles the probe result — metadata always present, transcript only when
     * a caption track was available and successfully fetched.
     */
    public record ProbeResult(SourceMetadata metadata, Optional<String> transcript) {}
}
