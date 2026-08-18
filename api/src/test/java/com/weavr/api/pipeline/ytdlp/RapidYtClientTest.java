package com.weavr.api.pipeline.ytdlp;

import java.time.Duration;
import java.util.Optional;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F4 (bounded retry with jitter, distinguishing 429/5xx from 404), F6 (rank
 * caption tracks by parsed prose rather than first-listed), and F8 (widened
 * URL coverage) from docs/extraction-architecture.md's Phase 2.
 *
 * <p>{@code MockRestServiceServer} is bound to the builder before {@code
 * RapidYtClient}'s constructor {@code .clone()}s it twice (once for the
 * RapidAPI-headers client, once for the plain caption-fetch client) — both
 * clones are exercised below, which is itself the empirical answer to
 * whether {@code RestClient.Builder#clone()} carries the mock request
 * factory over (it does).
 */
class RapidYtClientTest {

    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String METADATA_URL = RapidYtProperties.BASE_URL + "/dl?id=" + VIDEO_ID;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private RapidYtClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RapidYtProperties props = new RapidYtProperties("test-key", Duration.ofSeconds(2));
        client = new RapidYtClient(props, builder, MAPPER);
    }

    private static String metadataJson(String captionTracksJson) {
        return """
                {
                  "status": "OK",
                  "title": "Never Gonna Give You Up",
                  "channelTitle": "Rick Astley",
                  "description": "The official video",
                  "lengthSeconds": 212,
                  "thumbnail": [{"url": "https://example.com/thumb.jpg"}],
                  "captions": { "captionTracks": %s }
                }
                """.formatted(captionTracksJson);
    }

    private static String timedText(String text) {
        return "<transcript><p t=\"0\" d=\"1000\">" + text + "</p></transcript>";
    }

    /**
     * The real shape. A live {@code baseUrl} taken from a real RapidAPI
     * response, fetched for real, came back exactly like this — {@code
     * <text start dur>}, never {@code <p>} — because RapidAPI's baseUrl
     * carries no {@code fmt=srv3} parameter. The old parser only collected
     * {@code <p>} and returned "" for every real transcript this client ever
     * fetched; every test above used the wrong shape and could not have
     * caught it.
     */
    private static final String REAL_RAPIDAPI_TIMEDTEXT = """
            <?xml version="1.0" encoding="utf-8" ?><transcript>\
            <text start="0.12" dur="4.08">this is the easiest laziest and most</text>\
            <text start="2.28" dur="4.32">delicious homemade bread recipe which</text>\
            <text start="4.2" dur="4.76">requires absolutely no needing and only</text>\
            </transcript>""";

    @Test
    void parsesTheRealNonSrv3TimedTextShapeRapidApiActuallyReturns() {
        String parsed = RapidYtClient.parseTimedText(REAL_RAPIDAPI_TIMEDTEXT);

        assertThat(parsed).isEqualTo("this is the easiest laziest and most "
                + "delicious homemade bread recipe which "
                + "requires absolutely no needing and only");
    }

    @Test
    void probeReturnsARealTranscriptFromTheRealTimedTextShape() {
        String trackUrl = "https://youtube.com/timedtext?lang=en";
        String tracks = """
                [{"languageCode": "en", "baseUrl": "%s"}]
                """.formatted(trackUrl);

        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(metadataJson(tracks), MediaType.APPLICATION_JSON));
        server.expect(requestTo(trackUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REAL_RAPIDAPI_TIMEDTEXT, MediaType.TEXT_XML));

        Optional<RapidYtClient.ProbeResult> result = client.probe("https://youtube.com/watch?v=" + VIDEO_ID);

        assertThat(result).isPresent();
        assertThat(result.get().transcript()).isPresent();
        assertThat(result.get().transcript().get()).contains("easiest laziest", "homemade bread recipe");
    }

    // --- gating, no network at all ---

    @Test
    void disabledClientNeverMakesARequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer disabledServer = MockRestServiceServer.bindTo(builder).build();
        RapidYtClient disabledClient = new RapidYtClient(
                new RapidYtProperties("", Duration.ofSeconds(2)), builder, MAPPER);

        assertThat(disabledClient.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isEmpty();
        disabledServer.verify();
    }

    @Test
    void aNonYoutubeUrlNeverMakesARequest() {
        assertThat(client.probe("https://example.com/article")).isEmpty();
        server.verify();
    }

    // --- F4: retry vs. no-retry ---

    @Test
    void retriesOnTooManyRequestsThenSucceeds() {
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(metadataJson("[]"), MediaType.APPLICATION_JSON));

        Optional<RapidYtClient.ProbeResult> result = client.probe("https://youtube.com/watch?v=" + VIDEO_ID);

        assertThat(result).isPresent();
        assertThat(result.get().metadata().title()).isEqualTo("Never Gonna Give You Up");
        server.verify();
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(metadataJson("[]"), MediaType.APPLICATION_JSON));

        assertThat(client.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isPresent();
        server.verify();
    }

    @Test
    void exhaustsRetriesOnRepeatedServerErrorsAndReturnsEmpty() {
        server.expect(requestTo(METADATA_URL)).andRespond(withServerError());
        server.expect(requestTo(METADATA_URL)).andRespond(withServerError());
        server.expect(requestTo(METADATA_URL)).andRespond(withServerError());

        assertThat(client.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isEmpty();
        // Exactly 3 attempts (1 + 2 retries) — a 4th would fail server.verify()
        // below since no further expectation is registered.
        server.verify();
    }

    @Test
    void doesNotRetryANotFound() {
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isEmpty();
        // Only one expectation was registered — verify() fails if a retry fired
        // a second request MockRestServiceServer had nothing queued to answer.
        server.verify();
    }

    @Test
    void doesNotRetryAnUnauthorized() {
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isEmpty();
        server.verify();
    }

    @Test
    void rapidApiReportingNonOkStatusIsNotRetried() {
        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\": \"FAIL\"}", MediaType.APPLICATION_JSON));

        assertThat(client.probe("https://youtube.com/watch?v=" + VIDEO_ID)).isEmpty();
        server.verify();
    }

    // --- F6: rank caption tracks by parsed prose ---

    @Test
    void picksTheLongestParsedTranscriptAcrossCaptionTracks() {
        String shortUrl = "https://youtube.com/timedtext?lang=en";
        String longUrl = "https://youtube.com/timedtext?lang=es";
        String tracks = """
                [
                  {"languageCode": "en", "baseUrl": "%s"},
                  {"languageCode": "es", "baseUrl": "%s"}
                ]
                """.formatted(shortUrl, longUrl);

        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(metadataJson(tracks), MediaType.APPLICATION_JSON));
        server.expect(requestTo(shortUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(timedText("short"), MediaType.TEXT_XML));
        server.expect(requestTo(longUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(timedText("a much longer transcript body than the other track"),
                        MediaType.TEXT_XML));

        Optional<RapidYtClient.ProbeResult> result = client.probe("https://youtube.com/watch?v=" + VIDEO_ID);

        assertThat(result).isPresent();
        assertThat(result.get().transcript()).isPresent();
        assertThat(result.get().transcript().get()).isEqualTo("a much longer transcript body than the other track");
        server.verify();
    }

    @Test
    void aFailedCaptionTrackDoesNotSinkTheOthers() {
        String badUrl = "https://youtube.com/timedtext?lang=en";
        String goodUrl = "https://youtube.com/timedtext?lang=es";
        String tracks = """
                [
                  {"languageCode": "en", "baseUrl": "%s"},
                  {"languageCode": "es", "baseUrl": "%s"}
                ]
                """.formatted(badUrl, goodUrl);

        server.expect(requestTo(METADATA_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(metadataJson(tracks), MediaType.APPLICATION_JSON));
        server.expect(requestTo(badUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(requestTo(goodUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(timedText("the surviving transcript"), MediaType.TEXT_XML));

        Optional<RapidYtClient.ProbeResult> result = client.probe("https://youtube.com/watch?v=" + VIDEO_ID);

        assertThat(result).isPresent();
        assertThat(result.get().transcript()).contains("the surviving transcript");
    }

    // --- F8: widened URL coverage ---

    @Test
    void extractsIdFromAllSupportedUrlShapes() {
        assertThat(RapidYtClient.extractVideoId("https://youtube.com/watch?v=" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://www.youtube.com/watch?v=" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://youtu.be/" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://youtube.com/shorts/" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://youtube.com/embed/" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://youtube.com/live/" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://m.youtube.com/live/" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://m.youtube.com/watch?v=" + VIDEO_ID)).isEqualTo(VIDEO_ID);
        assertThat(RapidYtClient.extractVideoId("https://music.youtube.com/watch?v=" + VIDEO_ID)).isEqualTo(VIDEO_ID);
    }

    @Test
    void unrelatedUrlsExtractNoId() {
        assertThat(RapidYtClient.extractVideoId("https://example.com/article")).isNull();
        assertThat(RapidYtClient.extractVideoId("https://vimeo.com/12345678")).isNull();
        assertThat(RapidYtClient.extractVideoId(null)).isNull();
    }
}
