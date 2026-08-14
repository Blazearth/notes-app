package com.weavr.extraction.artifact;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 4's real artifact storage — the shared, private Supabase bucket,
 * under a per-save prefix, behind an opaque signed reference
 * (docs/extraction-architecture.md Part D / Phase 4). Replaces Phase 3's
 * local-disk-and-in-memory-registry stand-in: bytes now live in
 * {@link ArtifactBlobStorage} (backed by {@link SupabaseArtifactStorage}),
 * which survives a restart and works across more than one running instance.
 *
 * <p>The {@code ref} keeps exactly the contract callers on both sides of the
 * wire are written against: opaque, HMAC-signed, and expiring. What changed
 * is what it takes to redeem one — the Phase 3 version needed an in-memory
 * registry to translate an id back to a local file; this version embeds the
 * per-save prefix in the signed payload itself, so {@link #read} can
 * reconstruct the object's storage path (and therefore survive a restart)
 * with no registry lookup at all, verifying the signature before ever
 * touching storage.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String REF_PREFIX = "sig:";
    /** Separator inside the signed payload — never '/', so a ref alone never spells out a storage path. */
    private static final String PREFIX_ID_SEPARATOR = ":";
    /** How much of a caller-supplied prefix (e.g. a save id) survives sanitisation. */
    private static final int MAX_PREFIX_LENGTH = 80;

    private final ArtifactProperties properties;
    private final ArtifactBlobStorage storage;
    private final byte[] secretKey;

    public record StoredArtifact(String kind, String ref, long bytes, Instant expiresAt) {
    }

    ArtifactStore(ArtifactProperties properties, ArtifactBlobStorage storage) {
        this.properties = properties;
        this.storage = storage;
        this.secretKey = resolveSecret(properties.secret());
    }

    private static byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        // A ref signed with an ephemeral key stops validating the moment this
        // instance restarts, or a sibling instance receives the redemption
        // request instead of the one that minted it — set
        // weavr.extraction.artifact.secret explicitly once more than one
        // instance is deployed.
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.info("weavr.extraction.artifact.secret not set — generated an ephemeral signing key for this run");
        return random;
    }

    /**
     * @param prefix groups the artifact under, e.g., the save id carried in
     *               {@code Idempotency-Key} — sanitised to a safe charset,
     *               or replaced with a random id if blank/absent
     * @param kind   a short label the caller assigns (e.g. "thumbnail", "audio", "frame")
     */
    public StoredArtifact put(String prefix, String kind, byte[] bytes) {
        String safePrefix = sanitizePrefix(prefix);
        String id = UUID.randomUUID().toString();
        storage.put(objectPath(safePrefix, id), bytes);

        Instant expiresAt = Instant.now().plus(properties.ttl());
        String ref = sign(safePrefix, id, expiresAt);
        return new StoredArtifact(kind, ref, bytes.length, expiresAt);
    }

    /**
     * @return the bytes, or empty if the ref is malformed, forged, expired, or
     *         the object is missing. A successful read deletes the artifact
     *         from storage — Phase 4's "a completed save leaves no artifact
     *         behind" gate, satisfied the moment it's consumed rather than
     *         waiting for {@link #sweep}.
     */
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
        String path = objectPath(parsed.prefix, parsed.id);
        Optional<byte[]> bytes = storage.get(path);
        bytes.ifPresent(b -> storage.delete(path));
        return bytes;
    }

    /**
     * Deletes anything left in the bucket past its TTL — the safety net for
     * an artifact nobody ever redeemed (a crashed job, a response that never
     * reached the backend). {@link #read}'s delete-on-consumption already
     * handles the ordinary path; this only ever touches orphans, and it is
     * best-effort by design — a listing failure skips that prefix rather
     * than failing the whole sweep.
     */
    @Scheduled(fixedDelay = 60_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(properties.ttl());
        int deleted = 0;
        for (String prefix : safeListPrefixes()) {
            for (ArtifactBlobStorage.Entry entry : safeList(prefix)) {
                if (entry.createdAt() != null && entry.createdAt().isBefore(cutoff)) {
                    storage.delete(prefix + "/" + entry.name());
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            log.info("Artifact sweep deleted {} orphaned object(s)", deleted);
        }
    }

    private List<String> safeListPrefixes() {
        try {
            return storage.listPrefixes();
        } catch (RuntimeException e) {
            log.warn("Artifact sweep could not list prefixes: {}", e.toString());
            return List.of();
        }
    }

    private List<ArtifactBlobStorage.Entry> safeList(String prefix) {
        try {
            return storage.list(prefix);
        } catch (RuntimeException e) {
            log.warn("Artifact sweep could not list prefix {}: {}", prefix, e.toString());
            return List.of();
        }
    }

    private static String objectPath(String prefix, String id) {
        return prefix + "/" + id;
    }

    private static String sanitizePrefix(String prefix) {
        if (prefix == null) {
            return UUID.randomUUID().toString();
        }
        String cleaned = prefix.replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > MAX_PREFIX_LENGTH ? cleaned.substring(0, MAX_PREFIX_LENGTH) : cleaned;
    }

    private String sign(String prefix, String id, Instant expiresAt) {
        String payload = prefix + PREFIX_ID_SEPARATOR + id + "." + expiresAt.getEpochSecond();
        byte[] mac = hmac(payload);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
        return REF_PREFIX + payload + "." + encoded;
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

    private record Parsed(String prefix, String id, Instant expiresAt, String payload, byte[] signature) {
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
        int epochDot = payload.lastIndexOf('.');
        if (epochDot < 0) {
            return null;
        }
        String prefixAndId = payload.substring(0, epochDot);
        String epochPart = payload.substring(epochDot + 1);
        int sep = prefixAndId.lastIndexOf(PREFIX_ID_SEPARATOR);
        if (sep < 0) {
            return null;
        }
        String prefix = prefixAndId.substring(0, sep);
        String id = prefixAndId.substring(sep + 1);
        try {
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(epochPart));
            byte[] signature = Base64.getUrlDecoder().decode(sigPart);
            return new Parsed(prefix, id, expiresAt, payload, signature);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean validSignature(Parsed parsed) {
        byte[] expected = hmac(parsed.payload);
        return MessageDigest.isEqual(expected, parsed.signature);
    }
}
