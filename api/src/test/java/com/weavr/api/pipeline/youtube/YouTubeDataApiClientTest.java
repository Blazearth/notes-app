package com.weavr.api.pipeline.youtube;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import com.weavr.api.pipeline.health.ExtractionFailureCategory;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Response shapes follow Google's documented {@code videos.list} partial
 * response and standard error body. Mocked, not live: no Data API key exists
 * in this project's .env yet (see the final report).
 */
class YouTubeDataApiClientTest {

    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String KEY = "test-key-not-real";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private YouTubeDataApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new YouTubeDataApiClient(new YouTubeDataApiProperties(KEY, Duration.ofSeconds(2)), builder, MAPPER);
    }

    private static String videoJson(String snippetJson, String duration) {
        return """
                {"items": [{"id": "%s", "snippet": %s, "contentDetails": {"duration": "%s"}}]}
                """.formatted(VIDEO_ID, snippetJson, duration);
    }

    private static final String FULL_SNIPPET = """
            {"title": "One-pan lemon chicken",
             "description": "4 chicken thighs, 1 lemon, 3 garlic cloves. Roast at 220C for 35 minutes.",
             "channelTitle": "Weeknight Kitchen",
             "thumbnails": {
               "default": {"url": "https://i.ytimg.com/vi/x/default.jpg"},
               "high": {"url": "https://i.ytimg.com/vi/x/hqdefault.jpg"},
               "maxres": {"url": "https://i.ytimg.com/vi/x/maxresdefault.jpg"}}}
            """;

    private static String errorJson(int code, String reason) {
        return """
                {"error": {"code": %d, "message": "irrelevant",
                  "errors": [{"domain": "youtube.quota", "reason": "%s"}]}}
                """.formatted(code, reason);
    }

    @Test
    void mapsASuccessfulResponseIntoTheSharedMetadataShape() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL + "/videos")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("id", VIDEO_ID))
                .andExpect(queryParam("part", "snippet,contentDetails"))
                .andRespond(withSuccess(videoJson(FULL_SNIPPET, "PT4M13S"), MediaType.APPLICATION_JSON));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.SUCCESS);
        SourceMetadata metadata = outcome.metadata().orElseThrow();
        assertThat(metadata.id()).isEqualTo(VIDEO_ID);
        assertThat(metadata.title()).isEqualTo("One-pan lemon chicken");
        assertThat(metadata.description()).contains("Roast at 220C");
        assertThat(metadata.uploader()).isEqualTo("Weeknight Kitchen");
        assertThat(metadata.durationSeconds()).isEqualTo(253.0);
        assertThat(metadata.thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/x/maxresdefault.jpg");
        server.verify();
    }

    /** The Data API can't serve other people's captions — this client must never imply it did. */
    @Test
    void neverClaimsCaptions() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess(videoJson(FULL_SNIPPET, "PT1M"), MediaType.APPLICATION_JSON));

        SourceMetadata metadata = client.fetch(VIDEO_ID).metadata().orElseThrow();

        assertThat(metadata.captionLanguages()).isEmpty();
        assertThat(metadata.autoCaptionLanguages()).isEmpty();
        assertThat(metadata.hasCaptions()).isFalse();
    }

    /** A key in the URL would leak into logs via Spring's "I/O error on GET request for <url>". */
    @Test
    void sendsTheKeyAsAHeaderAndNeverInTheUrl() {
        server.expect(requestTo(not(containsString(KEY))))
                .andExpect(header("X-Goog-Api-Key", KEY))
                .andRespond(withSuccess(videoJson(FULL_SNIPPET, "PT1M"), MediaType.APPLICATION_JSON));

        client.fetch(VIDEO_ID);

        server.verify();
    }

    @Test
    void requestsOnlyTheFieldsItUses() {
        server.expect(queryParam("fields", YouTubeDataApiClient.FIELDS))
                .andRespond(withSuccess(videoJson(FULL_SNIPPET, "PT1M"), MediaType.APPLICATION_JSON));

        client.fetch(VIDEO_ID);

        server.verify();
    }

    @Test
    void toleratesAMissingTitleAndDescription() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess(videoJson("{\"channelTitle\": \"Someone\"}", "PT30S"),
                        MediaType.APPLICATION_JSON));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.SUCCESS);
        assertThat(outcome.metadata().orElseThrow().title()).isNull();
        assertThat(outcome.metadata().orElseThrow().description()).isNull();
        assertThat(outcome.metadata().orElseThrow().thumbnailUrl()).isNull();
    }

    /** videos.list answers 200 + no items for deleted, private and nonexistent videos alike. */
    @Test
    void anEmptyItemsListMeansTheVideoIsUnavailable() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess("{\"items\": []}", MediaType.APPLICATION_JSON));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.CONTENT_UNAVAILABLE);
        assertThat(outcome.metadata()).isEmpty();
    }

    @Test
    void quotaExhaustionIsA403ButCategorisedAsARateLimit() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON).body(errorJson(403, "quotaExceeded")));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.HTTP_429);
        assertThat(outcome.httpStatus()).isEqualTo(403);
        assertThat(outcome.detail()).isEqualTo("quotaExceeded");
    }

    @Test
    void anyOther403StaysA403() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON).body(errorJson(403, "accessNotConfigured")));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.HTTP_403);
        assertThat(outcome.detail()).isEqualTo("accessNotConfigured");
    }

    @Test
    void a404IsContentUnavailable() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetch(VIDEO_ID).category()).isEqualTo(ExtractionFailureCategory.CONTENT_UNAVAILABLE);
    }

    @Test
    void aBadKeyIsAProviderError() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON).body(errorJson(400, "keyInvalid")));

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.PROVIDER_ERROR);
        assertThat(outcome.detail()).isEqualTo("keyInvalid");
    }

    @Test
    void aServerErrorWithAnUnparseableBodyIsStillHandled() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL))).andRespond(withServerError());

        YouTubeDataApiClient.Outcome outcome = client.fetch(VIDEO_ID);

        assertThat(outcome.category()).isEqualTo(ExtractionFailureCategory.PROVIDER_ERROR);
        assertThat(outcome.httpStatus()).isEqualTo(500);
        assertThat(outcome.detail()).isNull();
    }

    @Test
    void networkErrorsAndTimeoutsAreSeparated() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withException(new IOException("connection reset")));
        assertThat(client.fetch(VIDEO_ID).category()).isEqualTo(ExtractionFailureCategory.NETWORK_ERROR);

        server.reset();
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThat(client.fetch(VIDEO_ID).category()).isEqualTo(ExtractionFailureCategory.TIMEOUT);
    }

    @Test
    void malformedResponsesAreProviderErrors() {
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));
        assertThat(client.fetch(VIDEO_ID).category()).isEqualTo(ExtractionFailureCategory.PROVIDER_ERROR);

        server.reset();
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess("{\"kind\": \"youtube#videoListResponse\"}", MediaType.APPLICATION_JSON));
        assertThat(client.fetch(VIDEO_ID).detail()).isEqualTo("no_items_array");

        server.reset();
        server.expect(requestTo(startsWith(YouTubeDataApiClient.BASE_URL)))
                .andRespond(withSuccess("{\"items\": [{\"id\": \"" + VIDEO_ID + "\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.fetch(VIDEO_ID).detail()).isEqualTo("no_snippet");
    }

    @Test
    void noKeyMeansNoRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer untouched = MockRestServiceServer.bindTo(builder).build();
        YouTubeDataApiClient unconfigured = new YouTubeDataApiClient(
                new YouTubeDataApiProperties("  ", null), builder, MAPPER);

        YouTubeDataApiClient.Outcome outcome = unconfigured.fetch(VIDEO_ID);

        assertThat(unconfigured.enabled()).isFalse();
        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.detail()).isEqualTo("not_configured");
        untouched.verify();
    }

    @Test
    void anInvalidVideoIdNeverSpendsQuota() {
        YouTubeDataApiClient.Outcome outcome = client.fetch("not-an-id");

        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.detail()).isEqualTo("invalid_video_id");
        server.verify();
    }

    @Test
    void parsesIsoDurations() {
        assertThat(YouTubeDataApiClient.durationSeconds("PT4M13S")).isEqualTo(253.0);
        assertThat(YouTubeDataApiClient.durationSeconds("PT1H2M")).isEqualTo(3720.0);
        assertThat(YouTubeDataApiClient.durationSeconds("P1DT1S")).isEqualTo(86401.0);
        assertThat(YouTubeDataApiClient.durationSeconds("P0D")).isNull();
        assertThat(YouTubeDataApiClient.durationSeconds("nonsense")).isNull();
        assertThat(YouTubeDataApiClient.durationSeconds(null)).isNull();
    }
}
