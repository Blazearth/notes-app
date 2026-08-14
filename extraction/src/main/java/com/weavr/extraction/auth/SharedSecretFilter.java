package com.weavr.extraction.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The service's only access control (docs/extraction-architecture.md Part D
 * Security): a shared bearer secret between backend and service, rejected
 * with 401 when absent, with no anonymous path. Deployed as a private
 * service with no public ingress (Phase 6), so this is defence in depth
 * rather than the only control — but it is real either way, since "private"
 * is a network-layer property this process cannot itself verify.
 *
 * <p>{@code /health} is exempt — a platform's own health checker has no
 * bearer token to send, and health has to answer before a caller can even
 * know the service's own auth is misconfigured.
 */
@Component
public class SharedSecretFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties properties;

    SharedSecretFilter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/health".equals(path) || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.configured() || !authorized(request)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.sharedSecret().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false,\"error\":{\"code\":\"AUTH_REQUIRED\","
                        + "\"message\":\"Missing or invalid service credentials.\",\"retryable\":false}}");
    }
}
