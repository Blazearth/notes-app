package com.weavr.api.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The app signs in with the Supabase client and sends the Supabase JWT here.
 * Spring validates it as an OAuth2 resource server.
 *
 * <p>Spring connects to Postgres with a service role, so <strong>RLS is not the
 * access-control boundary for API traffic</strong>. Authorization lives in the
 * service layer, keyed off the {@code sub} claim. This class only establishes
 * <em>authentication</em>.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                // Stateless bearer-token API: no cookies, so no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // RevenueCat posts here with its own shared-secret auth
                        // header, verified in the billing package - not a JWT.
                        .requestMatchers(HttpMethod.POST, "/v1/webhooks/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    /**
     * Built by hand rather than via {@code issuer-uri} so that issuer and
     * audience checks are explicit, and so startup never depends on Supabase
     * serving an OIDC discovery document.
     */
    @Bean
    JwtDecoder jwtDecoder(SupabaseProperties supabase) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(supabase.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                // exp / nbf, with the default clock skew
                JwtValidators.createDefault(),
                new JwtIssuerValidator(supabase.issuer()),
                audienceValidator(supabase.jwtAudience()))));
        return decoder;
    }

    /**
     * Supabase writes {@code aud} as a bare string; Nimbus normalises it to a
     * list. Reading it through {@link Jwt#getAudience()} avoids depending on
     * which of the two shapes arrives.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expected) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "Required audience '%s' is missing".formatted(expected),
                null);
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
