package com.weavr.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * One deployable: the REST API and, from Phase 2, the ingestion/AI pipeline.
 * Supabase is a managed Postgres + Auth + Storage host, not the backend.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WeavrApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeavrApiApplication.class, args);
    }
}
