package com.weavr.extraction.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.weavr.extraction.api.dto.SuccessResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caches a completed extraction by its {@code Idempotency-Key}
 * (docs/extraction-architecture.md Part D: "carries the save id, so the
 * backend's existing retry semantics do not produce duplicate extractions or
 * duplicate artifacts").
 *
 * <p><b>In-memory only, and stated as such rather than hidden</b> — like
 * {@link com.weavr.extraction.artifact.ArtifactStore}, this does not survive
 * a restart or work across more than one running instance. A retry that
 * lands on a different instance, or after a redeploy, simply re-extracts;
 * the backend's own idempotency (V2's partial unique index on
 * {@code saves(user_id, idempotency_key)}) is the durable guarantee, and this
 * cache exists only to avoid paying for the same extraction twice inside
 * that window, not to be the guarantee itself.
 */
@Component
class IdempotencyCache {

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final IdempotencyProperties properties;

    IdempotencyCache(IdempotencyProperties properties) {
        this.properties = properties;
    }

    private record Entry(SuccessResponse response, Instant expiresAt) {
    }

    Optional<SuccessResponse> get(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Entry entry = cache.get(key);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    void put(String key, SuccessResponse response) {
        if (key == null || key.isBlank()) {
            return;
        }
        cache.put(key, new Entry(response, Instant.now().plus(properties.ttl())));
    }

    @Scheduled(fixedDelay = 60_000)
    void sweep() {
        Instant now = Instant.now();
        cache.values().removeIf(e -> now.isAfter(e.expiresAt()));
    }
}
