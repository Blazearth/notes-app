package com.weavr.extraction.api;

import com.weavr.extraction.artifact.ArtifactStore;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /artifacts?ref=...} — how the backend redeems the opaque,
 * signed {@code ref} an {@code /extract} result returned. Not part of the
 * doc's own worked example (which shows only the reference being minted),
 * but a service that hands back a reference has to have somewhere for it to
 * be redeemed — this is that endpoint. As of Phase 4 the bytes it returns
 * come from the real Supabase Storage bucket, not local disk, and a
 * successful read deletes the object — see {@link ArtifactStore#read}.
 *
 * <p>{@code ref} rides as a query parameter rather than a path segment: it
 * contains {@code .} characters (the store's own signing format), and a path
 * segment risks a framework's suffix-stripping heuristics treating the tail
 * as a file extension.
 */
@RestController
class ArtifactController {

    private final ArtifactStore artifacts;

    ArtifactController(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/artifacts")
    ResponseEntity<byte[]> get(@RequestParam String ref) {
        return artifacts.read(ref)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes))
                .orElseThrow(() -> new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE,
                        "That artifact is missing or has expired."));
    }
}
