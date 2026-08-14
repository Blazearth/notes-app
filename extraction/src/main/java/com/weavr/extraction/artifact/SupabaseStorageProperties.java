package com.weavr.extraction.artifact;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url        Supabase project URL, e.g. {@code https://xxxx.supabase.co}
 *                   — the same value as {@code api}'s {@code WEAVR_SUPABASE_URL},
 *                   since both deployables point at one Supabase project.
 * @param serviceKey Service-role key: full bucket access, bypasses RLS. Same
 *                   env var name as {@code api}'s {@code WEAVR_SUPABASE_SERVICE_KEY}
 *                   on purpose, so one secret value configures both services.
 * @param bucket     Must already exist and be PRIVATE, created out-of-band
 *                   (same as {@code api}'s own {@code screenshots} bucket) —
 *                   this class does not create it. Unlike that public bucket,
 *                   nothing stored here should ever be reachable except
 *                   through {@link ArtifactStore}'s own signed ref, which is
 *                   the whole reason it stays private.
 * @param timeout    Read/connect timeout for the Supabase Storage RestClient.
 */
@ConfigurationProperties(prefix = "weavr.extraction.supabase")
public record SupabaseStorageProperties(
        String url,
        String serviceKey,
        String bucket,
        Duration timeout
) {

    public SupabaseStorageProperties {
        if (bucket == null || bucket.isBlank()) bucket = "extraction-artifacts";
        if (timeout == null) timeout = Duration.ofSeconds(20);
    }
}
