package com.weavr.extraction.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param sharedSecret {@code WEAVR_EXTRACTION_SHARED_SECRET} — the bearer
 *                      token the backend authenticates with. Blank fails
 *                      every non-exempt request closed rather than opening
 *                      the service to anonymous callers (docs/extraction-architecture.md
 *                      Part D Security: "rejected with 401 when absent, with
 *                      no anonymous path") — the same "unset disables rather
 *                      than opens" rule the billing webhook secret already
 *                      follows in {@code api}.
 */
@ConfigurationProperties(prefix = "weavr.extraction.auth")
public record AuthProperties(String sharedSecret) {

    public boolean configured() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
