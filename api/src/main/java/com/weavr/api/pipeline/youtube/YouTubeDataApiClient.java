package com.weavr.api.pipeline.youtube;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import com.weavr.api.pipeline.health.ExtractionFailureCategory;
import com.weavr.api.pipeline.health.ExtractionFailureClassifier;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Title, description, channel, duration and thumbnail for one YouTube video,
 * from Google's own API — one {@code videos.list} call, 1 quota unit.
 *
 * <p><b>Metadata only, and it says so.</b> The Data API's {@code captions}
 * resource only lets a video's <em>owner</em> download tracks, so this client
 * never claims captions: the returned {@link SourceMetadata} always has empty
 * caption-language lists. A recipe or workout description is frequently the
 * whole payload anyway; when it isn't, the cascade treats this result as too
 * thin and moves on rather than pretending otherwise.
 *
 * <p>The key travels in the {@code X-Goog-Api-Key} header, never the query
 * string: Spring's {@code ResourceAccessException} message embeds the full
 * request URL, and a key in the URL would land in the logs on the first
 * network error.
 */
@Component
public class YouTubeDataApiClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeDataApiClient.class);

    static final String BASE_URL = "https://www.googleapis.com/youtube/v3";

    /** Partial response: only what {@link SourceMetadata} holds. Smaller payload, same quota cost. */
    static final String FIELDS =
            "items(id,snippet(title,description,channelTitle,thumbnails),contentDetails(duration))";

    /** Largest first — the card thumbnail should be the best one Google offers. */
    private static final List<String> THUMBNAIL_SIZES = List.of("maxres", "standard", "high", "medium", "default");

    private final YouTubeDataApiProperties props;
    private final RestClient http;
    private final ObjectMapper objectMapper;

    public YouTubeDataApiClient(YouTubeDataApiProperties props,
                                @Qualifier("youtubeData") RestClient.Builder builder,
                                ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = builder.clone().baseUrl(BASE_URL).build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * @param videoId an 11-character YouTube video ID
     * @return never null, never throws; {@code attempted == false} when no request was made
     */
    public Outcome fetch(String videoId) {
        if (!props.enabled()) {
            return Outcome.notAttempted("not_configured");
        }
        if (!YouTubeVideoIds.isValidId(videoId)) {
            // Can't be a real video — don't spend quota finding that out.
            return Outcome.notAttempted("invalid_video_id");
        }

        String body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/videos")
                            .queryParam("part", "snippet,contentDetails")
                            .queryParam("id", videoId)
                            .queryParam("fields", FIELDS)
                            .build())
                    .header("X-Goog-Api-Key", props.apiKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DataApiStatusException(res.getStatusCode().value(), errorReason(res.getBody()));
                    })
                    .body(String.class);
        } catch (DataApiStatusException e) {
            log.warn("YouTube Data API returned HTTP {} ({}) for {}", e.status, e.reason, videoId);
            return Outcome.failed(categorise(e.status, e.reason), e.status, e.reason);
        } catch (RuntimeException e) {
            ExtractionFailureCategory category = ExtractionFailureClassifier.fromException(e);
            log.warn("YouTube Data API call failed for {}: {}", videoId, category);
            return Outcome.failed(category, null, e.getClass().getSimpleName());
        }

        return parse(videoId, body);
    }

    private Outcome parse(String videoId, String body) {
        JsonNode root;
        try {
            root = body == null ? null : objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return Outcome.failed(ExtractionFailureCategory.PROVIDER_ERROR, 200, "malformed_json");
        }
        if (root == null || !root.path("items").isArray()) {
            return Outcome.failed(ExtractionFailureCategory.PROVIDER_ERROR, 200, "no_items_array");
        }
        JsonNode items = root.path("items");
        if (items.isEmpty()) {
            // videos.list answers 200 with no items for a deleted, private or
            // nonexistent video — Google's authoritative "no such public video".
            return Outcome.failed(ExtractionFailureCategory.CONTENT_UNAVAILABLE, 200, "no_such_video");
        }
        JsonNode item = items.get(0);
        JsonNode snippet = item.path("snippet");
        if (!snippet.isObject()) {
            return Outcome.failed(ExtractionFailureCategory.PROVIDER_ERROR, 200, "no_snippet");
        }

        // Missing title/description is tolerated, not an error: the cascade's
        // own "is this substantive" check decides whether it's enough.
        SourceMetadata metadata = new SourceMetadata(
                videoId,
                text(snippet, "title"),
                text(snippet, "description"),
                text(snippet, "channelTitle"),
                durationSeconds(text(item.path("contentDetails"), "duration")),
                thumbnail(snippet.path("thumbnails")),
                List.of(),
                List.of(),
                null);
        return Outcome.success(metadata);
    }

    /**
     * Google reports quota exhaustion as a 403 with a {@code reason}, not as a
     * 429. It behaves like a rate limit (clears at the daily reset), so it's
     * categorised as one; every other 403 (bad key, API not enabled) stays 403.
     */
    static ExtractionFailureCategory categorise(int status, String reason) {
        if (reason != null && switch (reason) {
            case "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded", "userRateLimitExceeded" -> true;
            default -> false;
        }) {
            return ExtractionFailureCategory.HTTP_429;
        }
        return ExtractionFailureClassifier.fromHttpStatus(status);
    }

    /** {@code error.errors[0].reason} from Google's standard error body; never the message, which can be long. */
    private String errorReason(java.io.InputStream bodyStream) {
        try {
            String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode reason = objectMapper.readTree(body).path("error").path("errors").path(0).path("reason");
            return reason.isTextual() ? reason.asString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asString().isBlank() ? value.asString() : null;
    }

    /** ISO-8601 ({@code PT4M13S}). A live stream reports {@code P0D}; anything unparseable is simply unknown. */
    static Double durationSeconds(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            long seconds = Duration.parse(iso).toSeconds();
            return seconds > 0 ? (double) seconds : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String thumbnail(JsonNode thumbnails) {
        for (String size : THUMBNAIL_SIZES) {
            String url = text(thumbnails.path(size), "url");
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /**
     * @param attempted false when no request was made (no key, invalid ID)
     * @param detail    a short stable code for logs — a Google {@code reason}, never a message or URL
     */
    public record Outcome(boolean attempted, Optional<SourceMetadata> metadata,
                          ExtractionFailureCategory category, Integer httpStatus, String detail) {

        public static Outcome notAttempted(String why) {
            return new Outcome(false, Optional.empty(), ExtractionFailureCategory.UNKNOWN, null, why);
        }

        public static Outcome success(SourceMetadata metadata) {
            return new Outcome(true, Optional.of(metadata), ExtractionFailureCategory.SUCCESS, 200, null);
        }

        public static Outcome failed(ExtractionFailureCategory category, Integer httpStatus, String detail) {
            return new Outcome(true, Optional.empty(), category, httpStatus, detail);
        }
    }

    private static final class DataApiStatusException extends RuntimeException {
        private final int status;
        private final String reason;

        DataApiStatusException(int status, String reason) {
            super("YouTube Data API returned HTTP " + status);
            this.status = status;
            this.reason = reason;
        }
    }
}
