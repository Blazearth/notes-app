package com.weavr.api.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Supabase is a managed Postgres + Auth + Storage host here, not the backend.
 * The only thing we need from it at the HTTP level is the token issuer.
 *
 * @param url         project base URL, e.g. {@code https://abcdefgh.supabase.co}
 * @param jwtAudience audience claim Supabase stamps on signed-in access tokens
 */
@Validated
@ConfigurationProperties(prefix = "weavr.supabase")
public record SupabaseProperties(

        @NotBlank String url,

        @NotBlank String jwtAudience
) {

    /** Issuer claim on a Supabase-minted access token. */
    public String issuer() {
        return trimmedUrl() + "/auth/v1";
    }

    public String jwkSetUri() {
        return issuer() + "/.well-known/jwks.json";
    }

    private String trimmedUrl() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
