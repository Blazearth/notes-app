package com.weavr.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One deployable: the REST API and, from Phase 2, the ingestion/AI pipeline.
 * Supabase is a managed Postgres + Auth + Storage host, not the backend.
 *
 * <p>{@code @EnableScheduling} is here for {@link
 * com.weavr.api.job.RetentionSweeper} — the pipeline's own bookkeeping tables
 * are the only scheduled work in the service. The job runner itself is not
 * scheduled: it owns a poller thread, because a fixed-rate schedule cannot
 * express "claim the next job the moment a worker frees up".
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WeavrApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeavrApiApplication.class, args);
    }
}
