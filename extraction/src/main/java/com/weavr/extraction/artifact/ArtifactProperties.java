package com.weavr.extraction.artifact;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret HMAC key signing artifact references. Blank generates a
 *               random key at startup — fine, since the store itself is
 *               in-memory-and-local-disk and does not survive a restart
 *               either, so a ref minted before a restart is unusable
 *               regardless of the key. Set explicitly only if refs must
 *               remain valid across a rolling deploy with more than one
 *               instance sharing a signing key — not the case yet
 *               (Phase 4 replaces this whole class with Supabase Storage).
 * @param ttl    how long an artifact and its ref stay valid. Short on
 *               purpose — an artifact exists only to cross the one HTTP hop
 *               back to the backend for the current save
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
