package com.weavr.api.config;

import com.weavr.api.analytics.AnalyticsProperties;
import com.weavr.api.asr.GroqProperties;
import com.weavr.api.embed.EmbeddingProperties;
import com.weavr.api.enrich.EnrichmentProperties;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.notification.NotificationProperties;
import com.weavr.api.pipeline.health.ExtractionAttemptRecorder;
import com.weavr.api.pipeline.health.ExtractionCanary;
import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.youtube.YouTubeDataApiProperties;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
import com.weavr.api.pipeline.ytdlp.RapidYtProperties;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * No test boots the full application (it needs the live database), so the
 * two new Spring-level decisions are checked here in a minimal context:
 * the {@code youtubeData}-qualified builder resolves into the Data API client
 * with its property-bound key, and the canary exists only when switched on.
 * Missing beans fail at startup, not compilation — CLAUDE.md's
 * {@code RestClient.Builder} trap — which is exactly what this catches.
 */
class ExtractionHealthWiringTest {

    @Configuration
    @EnableConfigurationProperties({GeminiProperties.class, GroqProperties.class, RapidYtProperties.class,
            YouTubeDataApiProperties.class, EnrichmentProperties.class, EmbeddingProperties.class,
            NotificationProperties.class, AnalyticsProperties.class})
    @Import({RestClientConfig.class, YouTubeDataApiClient.class, RapidYtClient.class,
            ExtractionAttemptRecorder.class, ExtractionCanary.class})
    static class Wiring {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(YtDlpClient.class, () -> mock(YtDlpClient.class))
            .withPropertyValues("weavr.gemini.api-key=test", "weavr.youtube-data-api.api-key=test-key");

    @Test
    void theDataApiClientGetsItsQualifiedBuilderAndKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(YouTubeDataApiClient.class).enabled()).isTrue();
        });
    }

    @Test
    void theCanaryIsAbsentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ExtractionCanary.class));
    }

    @Test
    void theCanaryIsWiredWhenEnabled() {
        runner.withPropertyValues("weavr.canary.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExtractionCanary.class);
                });
    }
}
