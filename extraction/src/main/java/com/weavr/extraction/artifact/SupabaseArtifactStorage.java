package com.weavr.extraction.artifact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real backing store behind {@link ArtifactStore} — the shared, private
 * Supabase Storage bucket, reached over its plain REST API (there is no
 * Supabase SDK for Java). {@link #put}/{@link #get}/{@link #delete} mirror
 * {@code api}'s own {@code SupabaseStorageClient} (upload is a {@code PUT}
 * with {@code x-upsert}, verified live against this project's bucket for
 * screenshots) — the same request shape, a different bucket.
 *
 * <p>{@link #listPrefixes()} and {@link #list(String)} — {@link ArtifactStore#sweep()}'s
 * only callers — use the {@code POST /object/list/{bucket}} shape the
 * Supabase JS client's own {@code .list()} wraps, but that specific call has
 * <b>not</b> been run against a real bucket from this codebase, unlike the
 * three CRUD calls above it. A wrong assumption there degrades to "the sweep
 * finds nothing," logged and swallowed by the caller — it cannot corrupt a
 * read or a write, since neither uses this class's listing code.
 */
@Component
class SupabaseArtifactStorage implements ArtifactBlobStorage {

    private static final Logger log = LoggerFactory.getLogger(SupabaseArtifactStorage.class);

    private final SupabaseStorageProperties props;
    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    SupabaseArtifactStorage(SupabaseStorageProperties props,
                             @Qualifier("supabaseStorage") RestClient.Builder builder,
                             ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.baseUrl = trimmedUrl(props.url()) + "/storage/v1";
        this.http = builder.clone()
                .defaultHeader("Authorization", "Bearer " + orBlank(props.serviceKey()))
                .build();
    }

    // Deliberately NOT a RestClient URI template (".uri(\"/object/{bucket}/{path}\", ...)")
    // — UriComponentsBuilder would percent-encode the '/' inside objectPath
    // (it separates the per-save prefix from the artifact id), corrupting the
    // very nesting this class exists to create. Building the full string
    // first, like api's own SupabaseStorageClient.upload does, sidesteps that.
    private String objectUrl(String objectPath) {
        return baseUrl + "/object/" + props.bucket() + "/" + objectPath;
    }

    @Override
    public void put(String objectPath, byte[] bytes) {
        try {
            http.put()
                    .uri(objectUrl(objectPath))
                    .header("x-upsert", "true")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ExtractionException(ErrorCode.STORAGE_ERROR, "Could not store an extracted artifact.", e);
        }
    }

    @Override
    public Optional<byte[]> get(String objectPath) {
        try {
            byte[] bytes = http.get()
                    .uri(objectUrl(objectPath))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new ArtifactNotFound();
                    })
                    .body(byte[].class);
            return Optional.ofNullable(bytes);
        } catch (ArtifactNotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Could not read artifact {} from storage: {}", objectPath, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String objectPath) {
        try {
            http.delete()
                    .uri(objectUrl(objectPath))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // Best-effort by contract (ArtifactBlobStorage#delete) — a failed
            // delete just means the sweep or the next redemption tries again.
            log.warn("Could not delete artifact {} from storage: {}", objectPath, e.toString());
        }
    }

    @Override
    public List<String> listPrefixes() {
        List<String> prefixes = new ArrayList<>();
        for (Entry entry : list("")) {
            if (entry.createdAt() == null) {
                prefixes.add(entry.name());
            }
        }
        return prefixes;
    }

    @Override
    public List<Entry> list(String prefix) {
        String json = http.post()
                .uri(baseUrl + "/object/list/" + props.bucket())
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(new ListRequest(prefix, 1000, new SortBy("created_at", "asc"))))
                .retrieve()
                .body(String.class);
        return parseEntries(json);
    }

    private List<Entry> parseEntries(String json) {
        List<Entry> entries = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return entries;
        }
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            return entries;
        }
        for (JsonNode node : root) {
            String name = node.path("name").asText(null);
            if (name == null) {
                continue;
            }
            entries.add(new Entry(name, parseInstant(node.path("created_at").asText(null))));
        }
        return entries;
    }

    private static Instant parseInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trimmedUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    private record ListRequest(String prefix, int limit, SortBy sortBy) {
    }

    private record SortBy(String column, String order) {
    }

    /** Internal control-flow signal only — never escapes {@link #get}. */
    private static final class ArtifactNotFound extends RuntimeException {
    }
}
