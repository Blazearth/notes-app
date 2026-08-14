package com.weavr.extraction.artifact;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract callers on both sides of the wire are written against: a
 * ref is opaque, signed, and expires (docs/extraction-architecture.md Part D:
 * "An artifact is an opaque signed reference with an expiry... the service's
 * storage layout stays private, and a leaked response body expires").
 *
 * <p>Runs against {@link InMemoryBlobStorage}, a trivial fake, rather than
 * the real {@link SupabaseArtifactStorage} — this class pins the signing,
 * expiry and delete-on-read logic in {@link ArtifactStore} itself, which is
 * pure and doesn't need real HTTP to exercise. {@code SupabaseArtifactStorageTest}
 * covers the HTTP boundary separately.
 */
class ArtifactStoreTest {

    private static ArtifactStore newStore(String secret, Duration ttl) {
        return new ArtifactStore(new ArtifactProperties(secret, ttl), new InMemoryBlobStorage());
    }

    @Test
    void roundTripsStoredBytes() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));

        ArtifactStore.StoredArtifact stored = store.put("save-1", "thumbnail", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(stored.ref())).isPresent()
                .get().isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refDoesNotExposeTheUnderlyingStoragePath() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));

        ArtifactStore.StoredArtifact stored = store.put("save-1", "frame", new byte[]{1, 2, 3});

        assertThat(stored.ref()).startsWith("sig:").doesNotContain("/").doesNotContain("\\");
    }

    @Test
    void aBlankOrAbsentPrefixIsReplacedRatherThanRejected() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));

        ArtifactStore.StoredArtifact fromNull = store.put(null, "frame", new byte[]{1});
        ArtifactStore.StoredArtifact fromBlank = store.put("   ", "frame", new byte[]{2});

        assertThat(store.read(fromNull.ref())).contains(new byte[]{1});
        assertThat(store.read(fromBlank.ref())).contains(new byte[]{2});
    }

    @Test
    void aSuccessfulReadDeletesTheArtifact() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));
        ArtifactStore.StoredArtifact stored = store.put("save-1", "frame", "once".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(stored.ref())).isPresent();
        // Phase 4's gate: a completed save leaves no artifact behind.
        assertThat(store.read(stored.ref())).isEmpty();
    }

    @Test
    void rejectsAForgedRef() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));
        ArtifactStore.StoredArtifact stored = store.put("save-1", "frame", "real".getBytes(StandardCharsets.UTF_8));

        // Flip the last character of the signature.
        String forged = stored.ref().substring(0, stored.ref().length() - 1) + "x";

        assertThat(store.read(forged)).isEmpty();
    }

    @Test
    void rejectsARefSignedWithADifferentSecret() {
        InMemoryBlobStorage sharedStorage = new InMemoryBlobStorage();
        ArtifactStore signer = new ArtifactStore(new ArtifactProperties("secret-one", Duration.ofMinutes(10)), sharedStorage);
        ArtifactStore reader = new ArtifactStore(new ArtifactProperties("secret-two", Duration.ofMinutes(10)), sharedStorage);

        ArtifactStore.StoredArtifact stored = signer.put("save-1", "frame", "data".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(stored.ref())).isEmpty();
    }

    @Test
    void rejectsAnExpiredRef() {
        ArtifactStore store = newStore("test-secret", Duration.ofMillis(1));
        ArtifactStore.StoredArtifact stored = store.put("save-1", "frame", "data".getBytes(StandardCharsets.UTF_8));

        await(Duration.ofMillis(20));

        assertThat(store.read(stored.ref())).isEmpty();
    }

    @Test
    void rejectsAnUnrecognisableRef() {
        ArtifactStore store = newStore("test-secret", Duration.ofMinutes(10));

        assertThat(store.read("not-a-ref-at-all")).isEmpty();
        assertThat(store.read(null)).isEmpty();
    }

    private static void await(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A minimal, in-memory stand-in for {@link SupabaseArtifactStorage}. */
    private static final class InMemoryBlobStorage implements ArtifactBlobStorage {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String objectPath, byte[] bytes) {
            objects.put(objectPath, bytes);
        }

        @Override
        public Optional<byte[]> get(String objectPath) {
            return Optional.ofNullable(objects.get(objectPath));
        }

        @Override
        public void delete(String objectPath) {
            objects.remove(objectPath);
        }

        @Override
        public List<String> listPrefixes() {
            return List.of();
        }

        @Override
        public List<Entry> list(String prefix) {
            return List.of();
        }
    }
}
