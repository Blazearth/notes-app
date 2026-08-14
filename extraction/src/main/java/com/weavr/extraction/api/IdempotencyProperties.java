package com.weavr.extraction.api;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param ttl how long a completed extraction is remembered by its Idempotency-Key */
@ConfigurationProperties(prefix = "weavr.extraction.idempotency")
public record IdempotencyProperties(Duration ttl) {

    public IdempotencyProperties {
        if (ttl == null) ttl = Duration.ofMinutes(10);
    }
}
