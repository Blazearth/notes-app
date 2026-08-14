package com.weavr.extraction.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The byte-storage boundary {@link ArtifactStore} signs references against.
 * {@link SupabaseArtifactStorage} is the only production implementation
 * (docs/extraction-architecture.md Part D / Phase 4) — kept as an interface
 * so {@code ArtifactStoreTest} can pin the signing/expiry contract against a
 * trivial in-memory fake without exercising real HTTP, the same separation
 * {@code SafeUrlFetcher} and its test already use.
 */
public interface ArtifactBlobStorage {

    void put(String objectPath, byte[] bytes);

    Optional<byte[]> get(String objectPath);

    /** Best-effort — a failed delete is logged by the caller, never thrown. */
    void delete(String objectPath);

    /** Top-level "folders" (per-save prefixes) currently in the bucket. */
    List<String> listPrefixes();

    /** Files directly under {@code prefix} (one level, not recursive). */
    List<Entry> list(String prefix);

    /**
     * @param name      the entry's name relative to the prefix it was listed under
     * @param createdAt null marks a sub-folder rather than a file — Supabase
     *                  Storage's list endpoint returns folders the same way,
     *                  with every timestamp field absent
     */
    record Entry(String name, Instant createdAt) {
    }
}
