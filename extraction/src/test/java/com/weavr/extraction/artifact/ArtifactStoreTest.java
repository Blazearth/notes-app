package com.weavr.extraction.artifact;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract callers on both sides of the wire are written against: a
 * ref is opaque, signed, and expires (docs/extraction-architecture.md Part D:
 * "An artifact is an opaque signed reference with an expiry... the service's
 * storage layout stays private, and a leaked response body expires").
 */
class ArtifactStoreTest {

    @Test
    void roundTripsStoredBytes() {
        ArtifactStore store = new ArtifactStore(new ArtifactProperties("test-secret", Duration.ofMinutes(10)));

        ArtifactStore.StoredArtifact stored = store.put("thumbnail", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(stored.ref())).isPresent()
                .get().isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refDoesNotExposeTheUnderlyingFilePath() {
        ArtifactStore store = new ArtifactStore(new ArtifactProperties("test-secret", Duration.ofMinutes(10)));

        ArtifactStore.StoredArtifact stored = store.put("frame", new byte[]{1, 2, 3});

        assertThat(stored.ref()).startsWith("sig:").doesNotContain("/").doesNotContain("\\");
    }

    @Test
    void rejectsAForgedRef() {
        ArtifactStore store = new ArtifactStore(new ArtifactProperties("test-secret", Duration.ofMinutes(10)));
        ArtifactStore.StoredArtifact stored = store.put("frame", "real".getBytes(StandardCharsets.UTF_8));

        // Flip the last character of the signature.
        String forged = stored.ref().substring(0, stored.ref().length() - 1) + "x";

        assertThat(store.read(forged)).isEmpty();
    }

    @Test
    void rejectsARefSignedWithADifferentSecret() {
        ArtifactStore signer = new ArtifactStore(new ArtifactProperties("secret-one", Duration.ofMinutes(10)));
        ArtifactStore reader = new ArtifactStore(new ArtifactProperties("secret-two", Duration.ofMinutes(10)));

        ArtifactStore.StoredArtifact stored = signer.put("frame", "data".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(stored.ref())).isEmpty();
    }

    @Test
    void rejectsAnExpiredRef() {
        ArtifactStore store = new ArtifactStore(new ArtifactProperties("test-secret", Duration.ofMillis(1)));
        ArtifactStore.StoredArtifact stored = store.put("frame", "data".getBytes(StandardCharsets.UTF_8));

        await(Duration.ofMillis(20));

        assertThat(store.read(stored.ref())).isEmpty();
    }

    @Test
    void rejectsAnUnrecognisableRef() {
        ArtifactStore store = new ArtifactStore(new ArtifactProperties("test-secret", Duration.ofMinutes(10)));

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
}
