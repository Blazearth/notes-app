package com.weavr.extraction.artifact;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret HMAC key signing artifact references. Blank generates a
 *               random key at startup — fine for a single instance, but as
 *               of Phase 4 the underlying bytes live in Supabase Storage and
 *               genuinely survive a restart, so an ephemeral key is now the
 *               one thing standing between "restart-safe" and "not": set
 *               this explicitly once more than one instance is deployed, or
 *               once redeploys should not silently orphan in-flight refs.
 * @param ttl    how long an artifact and its ref stay valid, and the window
 *               {@link ArtifactStore#sweep()} uses to judge an object
 *               orphaned. Short on purpose — an artifact exists only to
 *               cross the one HTTP hop back to the backend for the current
 *               save.
 */
@ConfigurationProperties(prefix = "weavr.extraction.artifact")
public record ArtifactProperties(
        String secret,
        Duration ttl
) {

    public ArtifactProperties {
        if (ttl == null) ttl = Duration.ofMinutes(10);
    }
}
