package com.weavr.api.enrich;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys for the external enrichment sources.
 *
 * <p>Every one is optional. A missing key disables its enricher and nothing
 * else — enrichment is an enhancement on a save that is already {@code ready}
 * and already searchable, so it must never be able to fail one.
 */
@ConfigurationProperties(prefix = "weavr.enrich")
public record EnrichmentProperties(

        boolean enabled,

        /**
         * TMDB <b>v4 read access token</b>, sent as a bearer — not the v3 API
         * key, which goes in a query parameter instead and will 401 here.
         * Enriches {@code movie}.
         */
        String tmdbApiKey,

        /** Google Places API key. Enriches {@code place}. */
        String googlePlacesApiKey,

        /**
         * Google Books API key (server key, no referrer restriction needed).
         * Free tier: ~1,000 requests/day. Enriches {@code book}.
         * https://console.cloud.google.com → Library → Books API → Credentials
         */
        String googleBooksApiKey,

        Duration timeout
) {

    public boolean tmdbConfigured() {
        return enabled && tmdbApiKey != null && !tmdbApiKey.isBlank();
    }

    public boolean placesConfigured() {
        return enabled && googlePlacesApiKey != null && !googlePlacesApiKey.isBlank();
    }

    public boolean booksConfigured() {
        return enabled && googleBooksApiKey != null && !googleBooksApiKey.isBlank();
    }
}
