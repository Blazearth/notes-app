package com.weavr.extraction.artifact;

import java.time.Duration;
import java.util.List;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the request shape {@link SupabaseArtifactStorage} sends for its three
 * verified-pattern calls (upload/download/delete, mirroring {@code api}'s
 * own {@code SupabaseStorageClient}) and the listing shape its own class doc
 * states has NOT been run against a real bucket.
 */
class SupabaseArtifactStorageTest {

    private static final String BASE = "https://proj.supabase.co";
    private static final String BUCKET = "test-artifacts";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private SupabaseArtifactStorage storage;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SupabaseStorageProperties props = new SupabaseStorageProperties(BASE, "svc-key", BUCKET, Duration.ofSeconds(5));
        storage = new SupabaseArtifactStorage(props, builder, MAPPER);
    }

    // --- put -------------------------------------------------------------------

    @Test
    void putUploadsWithUpsertAndTheServiceKey() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("x-upsert", "true"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer svc-key"))
                .andRespond(withSuccess());

        storage.put("save-1/abc", "hello".getBytes());

        server.verify();
    }

    @Test
    void putFailureBecomesAStorageError() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> storage.put("save-1/abc", "hello".getBytes()))
                .isInstanceOf(ExtractionException.class)
                .satisfies(e -> assertThat(((ExtractionException) e).code()).isEqualTo(ErrorCode.STORAGE_ERROR));
    }

    // --- get -------------------------------------------------------------------

    @Test
    void getReturnsTheStoredBytes() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("hello".getBytes(), MediaType.APPLICATION_OCTET_STREAM));

        assertThat(storage.get("save-1/abc")).contains("hello".getBytes());
    }

    @Test
    void getOnAMissingObjectIsEmptyNotAnException() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(storage.get("save-1/gone")).isEmpty();
    }

    @Test
    void getOnAServerErrorIsEmptyRatherThanThrown() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andRespond(withServerError());

        assertThat(storage.get("save-1/abc")).isEmpty();
    }

    // --- delete ------------------------------------------------------------------

    @Test
    void deleteSendsTheDeleteVerb() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        storage.delete("save-1/abc");

        server.verify();
    }

    @Test
    void deleteFailureIsSwallowedNotThrown() {
        server.expect(requestTo(BASE + "/storage/v1/object/" + BUCKET + "/save-1/abc"))
                .andRespond(withServerError());

        storage.delete("save-1/abc"); // must not throw

        server.verify();
    }

    // --- list / listPrefixes -----------------------------------------------------

    @Test
    void listParsesFilesAndSkipsEntriesWithNoName() {
        server.expect(requestTo(BASE + "/storage/v1/object/list/" + BUCKET))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        [
                          {"name": "abc", "created_at": "2026-08-15T10:00:00Z"},
                          {"name": null, "created_at": "2026-08-15T10:00:00Z"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<ArtifactBlobStorage.Entry> entries = storage.list("save-1");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo("abc");
        assertThat(entries.get(0).createdAt()).isNotNull();
    }

    @Test
    void listPrefixesKeepsOnlyEntriesWithNoCreatedAt() {
        server.expect(requestTo(BASE + "/storage/v1/object/list/" + BUCKET))
                .andRespond(withSuccess("""
                        [
                          {"name": "save-1", "id": null, "created_at": null},
                          {"name": "save-2/abc", "created_at": "2026-08-15T10:00:00Z"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertThat(storage.listPrefixes()).containsExactly("save-1");
    }

    @Test
    void listOfAnEmptyBucketIsAnEmptyList() {
        server.expect(requestTo(BASE + "/storage/v1/object/list/" + BUCKET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(storage.list("")).isEmpty();
    }
}
