package com.weavr.api.config;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around the Supabase Auth Admin REST API — the service-role-only
 * endpoints under {@code /auth/v1/admin}. Today this has exactly one caller,
 * account deletion, and exactly one method.
 */
@Component
public class SupabaseAdminClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAdminClient.class);

    private final SupabaseProperties props;
    private final RestClient http;

    SupabaseAdminClient(SupabaseProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.http = restClientBuilder.build();
    }

    /**
     * Deletes the Supabase auth user outright — not just our own
     * {@code profiles} row. GoTrue cascades {@code auth.sessions} and
     * {@code auth.refresh_tokens} away with the user, which is what actually
     * revokes the ability to sign in again and mint new access tokens.
     *
     * <p><b>This does not retroactively invalidate an access token already
     * issued.</b> {@code SecurityConfig} verifies a token against Supabase's
     * JWKS as a signed, self-contained JWT — no database lookup — so a token
     * still inside its own (short) expiry window keeps authenticating until
     * it expires on its own. That is a property of stateless JWT auth
     * generally, not something this call can fix; the app signs out and wipes
     * its local session the moment account deletion succeeds, which closes
     * the practical window for the device that asked for the deletion.
     *
     * <p>Idempotent: a 404 — already deleted, most likely a retried
     * account-deletion call after an earlier partial failure — is treated as
     * success rather than propagated.
     */
    public void deleteUser(UUID userId) {
        try {
            http.delete()
                    .uri(trimmedUrl() + "/auth/v1/admin/users/" + userId)
                    .header("Authorization", "Bearer " + props.serviceKey())
                    .header("apikey", props.serviceKey())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Deleted Supabase auth user {}", userId);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Supabase auth user {} was already deleted", userId);
        }
    }

    private String trimmedUrl() {
        String url = props.url();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
