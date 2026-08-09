package com.weavr.api.config;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around the Supabase Storage REST API.
 *
 * <p>Used only for server-initiated uploads (e.g., receiving a screenshot from
 * the Android share worker) — client-side uploads go directly from the app using
 * the JS Supabase client. Authentication uses the service-role key, which has
 * full bucket access and bypasses RLS.
 *
 * <p>Bucket and object-path ownership:
 * <ul>
 *   <li>Bucket: {@code weavr.supabase.storage-bucket} (default {@code screenshots})</li>
 *   <li>Path: {@code {userId}/{saveId}.jpg} — userId is the folder key so RLS
 *       policies can scope reads per-user if we add them later.</li>
 * </ul>
 */
@Component
public class SupabaseStorageClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageClient.class);

    private final SupabaseProperties props;
    private final RestClient http;

    SupabaseStorageClient(SupabaseProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.http = restClientBuilder.build();
    }

    /**
     * Uploads {@code bytes} to Supabase Storage and returns the public URL.
     *
     * <p>Uses an upsert ({@code x-upsert: true}) so a WorkManager retry that
     * re-sends the same save ID simply overwrites the object rather than
     * returning a 409.
     *
     * @param userId   owner — used as the folder prefix
     * @param saveId   save UUID — the filename (with {@code .jpg} suffix)
     * @param bytes    JPEG image bytes
     * @param mimeType MIME type of the image (e.g. {@code image/jpeg})
     * @return the publicly accessible URL for the uploaded object
     */
    public String upload(UUID userId, UUID saveId, byte[] bytes, String mimeType) {
        String bucket = bucket();
        String objectPath = userId + "/" + saveId + ".jpg";
        String baseUrl = trimmedUrl();
        String uploadUrl = baseUrl + "/storage/v1/object/" + bucket + "/" + objectPath;

        log.debug("Supabase Storage upload: save={} bucket={} path={} bytes={}",
                saveId, bucket, objectPath, bytes.length);

        http.put()
                .uri(uploadUrl)
                .header("Authorization", "Bearer " + props.serviceKey())
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(mimeType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();

        return baseUrl + "/storage/v1/object/public/" + bucket + "/" + objectPath;
    }

    private String bucket() {
        String b = props.storageBucket();
        return (b != null && !b.isBlank()) ? b : "screenshots";
    }

    private String trimmedUrl() {
        String url = props.url();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
