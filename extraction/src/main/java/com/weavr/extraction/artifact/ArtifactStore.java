package com.weavr.extraction.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A stand-in for Phase 4's real artifact storage (a per-save prefix in the
 * shared Supabase bucket, signed expiring references — docs/extraction-architecture.md
 * Part D / Phase 4). This one writes to local disk and keeps its registry in
 * memory, so it does not survive a restart and does not work across more than
 * one running instance — acceptable for standing Phase 3 up on its own, and
 * explicitly not the deliverable Phase 4 replaces this class with.
 *
 * <p>What it does keep from the real contract, because callers on both sides
 * of the wire are written against it: a {@code ref} is an <b>opaque, signed,
 * expiring token</b>, never a raw path or id — {@link #put} HMAC-signs
 * {@code id.expiresAtEpochSeconds} and {@link #read} verifies that signature
 * in constant time before touching the filesystem, so the storage layout
 * stays private and a leaked ref expires like the real thing will.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String REF_PREFIX = "sig:";

    private final ArtifactProperties properties;
    private final Path root;
    private final byte[] secretKey;
    private final Map<String, Entry> registry = new ConcurrentHashMap<>();

    public record StoredArtifact(String kind, String ref, long bytes, Instant expiresAt) {
    }

    private record Entry(Path path, Instant expiresAt) {
    }

    ArtifactStore(ArtifactProperties properties) {
        this.properties = properties;
        try {
            this.root = Files.createTempDirectory("weavr-extraction-artifacts-");
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the artifact store's temp directory", e);
        }
        this.secretKey = resolveSecret(properties.secret());
    }

    private static byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        // Ephemeral key: refs minted before a restart are unusable after one
        // regardless, since the registry itself is in-memory (see class doc).
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.info("weavr.extraction.artifact.secret not set — generated an ephemeral signing key for this run");
        return random;
    }

    /**
     * @param kind a short label the caller assigns (e.g. "thumbnail", "audio", "frame")
     */
    public StoredArtifact put(String kind, byte[] bytes) {
        String id = UUID.randomUUID().toString();
        Path path = root.resolve(id);
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new ExtractionException(ErrorCode.STORAGE_ERROR, "Could not store an extracted artifact.", e);
        }

        Instant expiresAt = Instant.now().plus(properties.ttl());
        registry.put(id, new Entry(path, expiresAt));
        String ref = sign(id, expiresAt);
        return new StoredArtifact(kind, ref, bytes.length, expiresAt);
    }

    /** @return the bytes, or empty if the ref is malformed, forged, expired, or unknown */
    public Optional<byte[]> read(String ref) {
        Parsed parsed = parse(ref);
        if (parsed == null) {
            return Optional.empty();
        }
        if (!validSignature(parsed)) {
            log.warn("Rejected an artifact ref with an invalid signature");
            return Optional.empty();
        }
        if (Instant.now().isAfter(parsed.expiresAt)) {
            return Optional.empty();
        }
        Entry entry = registry.get(parsed.id);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(entry.path()));
        } catch (IOException e) {
            log.warn("Artifact {} was registered but could not be read from disk", parsed.id, e);
            return Optional.empty();
        }
    }

    /** Deletes expired entries and their files — the store's own bounded-disk guarantee. */
    @Scheduled(fixedDelay = 60_000)
    void sweep() {
        Instant now = Instant.now();
        registry.entrySet().removeIf(e -> {
            boolean expired = now.isAfter(e.getValue().expiresAt());
            if (expired) {
                deleteQuietly(e.getValue().path());
            }
            return expired;
        });
    }

    @PreDestroy
    void deleteAll() {
        registry.values().forEach(e -> deleteQuietly(e.path()));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the OS reclaims temp space regardless.
        }
    }

    private String sign(String id, Instant expiresAt) {
        String payload = id + "." + expiresAt.getEpochSecond();
        byte[] mac = hmac(payload);
        String encoder = Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
        return REF_PREFIX + payload + "." + encoder;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private record Parsed(String id, Instant expiresAt, String payload, byte[] signature) {
    }

    private static Parsed parse(String ref) {
        if (ref == null || !ref.startsWith(REF_PREFIX)) {
            return null;
        }
        String rest = ref.substring(REF_PREFIX.length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        String payload = rest.substring(0, lastDot);
        String sigPart = rest.substring(lastDot + 1);
        int idDot = payload.lastIndexOf('.');
        if (idDot < 0) {
            return null;
        }
        String id = payload.substring(0, idDot);
        String epochPart = payload.substring(idDot + 1);
        try {
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(epochPart));
            byte[] signature = Base64.getUrlDecoder().decode(sigPart);
            return new Parsed(id, expiresAt, payload, signature);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean validSignature(Parsed parsed) {
        byte[] expected = hmac(parsed.payload);
        return MessageDigest.isEqual(expected, parsed.signature);
    }
}
