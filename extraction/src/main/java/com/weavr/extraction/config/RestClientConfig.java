package com.weavr.extraction.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.weavr.extraction.artifact.SupabaseStorageProperties;
import com.weavr.extraction.ytdlp.RapidYtProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Gives every {@link RestClient.Builder} bean a connect and read timeout —
 * ported from {@code api}'s own {@code RestClientConfig}, which exists
 * precisely because CLAUDE.md's F1 found every {@code RestClient} in this
 * codebase built from an injected builder with no request factory at all. A
 * hung socket here pins the same small job-concurrency pool this service was
 * split out to protect.
 *
 * <p>Each client with its own {@code weavr.*.timeout} property gets a
 * dedicated {@link Qualifier}-named bean; {@link com.weavr.extraction.pdf.PdfExtractor}
 * and {@link com.weavr.extraction.fetch.SafeUrlFetcher}, which inject a plain,
 * unqualified {@code RestClient.Builder}, fall back to {@link #restClientBuilder()}'s
 * default (Spring resolves the ambiguity by matching the constructor
 * parameter name {@code restClientBuilder} to this bean's name).
 *
 * <p>Deliberately does not touch how the various {@code *ClientTest} classes
 * construct their client under test: those bind {@code MockRestServiceServer}
 * to a bare {@code RestClient.builder()} obtained directly from the static
 * factory method, never through this Spring configuration.
 */
@Configuration
class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** For clients with no dedicated timeout property of their own. */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);

    @Bean
    @Scope("prototype")
    RestClient.Builder restClientBuilder() {
        return builder(DEFAULT_READ_TIMEOUT);
    }

    @Bean
    @Qualifier("rapidYt")
    @Scope("prototype")
    RestClient.Builder rapidYtRestClientBuilder(RapidYtProperties props) {
        return builder(props.timeout());
    }

    @Bean
    @Qualifier("supabaseStorage")
    @Scope("prototype")
    RestClient.Builder supabaseStorageRestClientBuilder(SupabaseStorageProperties props) {
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
