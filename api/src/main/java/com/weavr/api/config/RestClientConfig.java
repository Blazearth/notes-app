package com.weavr.api.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.weavr.api.asr.GroqProperties;
import com.weavr.api.embed.EmbeddingProperties;
import com.weavr.api.enrich.EnrichmentProperties;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.pipeline.ytdlp.RapidYtProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Gives every {@link RestClient.Builder} bean a connect and read timeout —
 * previously none had either (CLAUDE.md F1: a hung socket pinned the only job
 * worker, and Render runs concurrency 1, forever).
 *
 * <p>Each client's own {@code timeout()} config value (already declared, but
 * dead until now) becomes that client's read timeout via a dedicated
 * {@link Qualifier}-named bean; anything injecting a plain, unqualified
 * {@code RestClient.Builder} — {@code LinkExtractor}, {@code PdfExtractor},
 * {@code ProcessSaveHandler}, {@code SupabaseAdminClient}, {@code
 * SupabaseStorageClient} — falls back to {@link #restClientBuilder()}'s
 * default (Spring resolves the ambiguity by matching the constructor
 * parameter name {@code restClientBuilder} to this bean's name).
 *
 * <p>Deliberately does not touch the injection sites in the various
 * {@code *ClientTest} classes: those construct their client under test with a
 * bare {@code RestClient.builder()} obtained directly from the static
 * factory method, never through this Spring configuration, so
 * {@code MockRestServiceServer.bindTo(builder)} keeps intercepting exactly as
 * before.
 */
@Configuration
class RestClientConfig {

    /** Applies uniformly; a slow DNS/TCP handshake is not what any of these timeouts are tuned for. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** For clients with no dedicated timeout property of their own. */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);

    @Bean
    @Scope("prototype")
    RestClient.Builder restClientBuilder() {
        return builder(DEFAULT_READ_TIMEOUT);
    }

    @Bean
    @Qualifier("gemini")
    @Scope("prototype")
    RestClient.Builder geminiRestClientBuilder(GeminiProperties props) {
        return builder(props.timeout());
    }

    @Bean
    @Qualifier("groq")
    @Scope("prototype")
    RestClient.Builder groqRestClientBuilder(GroqProperties props) {
        return builder(props.timeout());
    }

    @Bean
    @Qualifier("rapidYt")
    @Scope("prototype")
    RestClient.Builder rapidYtRestClientBuilder(RapidYtProperties props) {
        return builder(props.timeout());
    }

    /** Shared by all three enrichers (TMDB, Places, Books) — they share one timeout property too. */
    @Bean
    @Qualifier("enrich")
    @Scope("prototype")
    RestClient.Builder enrichRestClientBuilder(EnrichmentProperties props) {
        return builder(props.timeout());
    }

    @Bean
    @Qualifier("embedding")
    @Scope("prototype")
    RestClient.Builder embeddingRestClientBuilder(EmbeddingProperties props) {
        return builder(props.timeout());
    }

    @Bean
    @Qualifier("notification")
    @Scope("prototype")
    RestClient.Builder notificationRestClientBuilder(com.weavr.api.notification.NotificationProperties props) {
        return builder(props.timeout());
    }

    private static RestClient.Builder builder(Duration readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(readTimeout));
    }

    private static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
