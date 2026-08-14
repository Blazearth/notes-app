package com.weavr.api.pipeline;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SSRF-blocked addresses never reach the mock at all — {@code requireSafe}
 * runs before any request is issued, so a wrongly-permissive check would
 * surface here as {@code MockRestServiceServer} complaining about an
 * unexpected request, not as this test's own assertion failing quietly.
 *
 * <p>The "safe" test cases use RFC 5737 documentation-only addresses
 * (203.0.113.0/24) as literal IPs in the URL — a numeric IP resolves with no
 * real DNS lookup, so these tests have no network dependency, and the
 * address is unambiguously not loopback/private/link-local/multicast.
 */
class SafeUrlFetcherTest {

    private static final long MAX_BYTES = 1024;

    private MockRestServiceServer server;
    private SafeUrlFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        fetcher = new SafeUrlFetcher(builder);
    }

    @Test
    void rejectsALoopbackLiteral() {
        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/secret", MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void rejectsALoopbackHostname() {
        assertThatThrownBy(() -> fetcher.fetch("http://localhost/secret", MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void rejectsCloudMetadataAddress() {
        // 169.254.169.254 — the link-local cloud-metadata endpoint F2 names explicitly.
        assertThatThrownBy(() -> fetcher.fetch("http://169.254.169.254/latest/meta-data/", MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void rejectsAPrivateRfc1918Address() {
        assertThatThrownBy(() -> fetcher.fetch("http://10.0.0.5/internal", MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void rejectsANonHttpScheme() {
        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd", MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void fetchesFromASafePublicAddress() {
        String url = "http://203.0.113.10/article";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("hello world".getBytes(StandardCharsets.UTF_8), null));

        byte[] bytes = fetcher.fetch(url, MAX_BYTES);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    void abortsStreamingWhenTheResponseExceedsTheByteCeiling() {
        String url = "http://203.0.113.10/huge";
        byte[] tooBig = "this response is way over the tiny ceiling set for this test".getBytes(StandardCharsets.UTF_8);
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(tooBig, null));

        assertThatThrownBy(() -> fetcher.fetch(url, 10))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void followsARedirectAndRevalidatesTheNewTarget() {
        String start = "http://203.0.113.10/start";
        String target = "http://203.0.113.20/final";
        server.expect(requestTo(start)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create(target)));
        server.expect(requestTo(target)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("redirected content".getBytes(StandardCharsets.UTF_8), null));

        byte[] bytes = fetcher.fetch(start, MAX_BYTES);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("redirected content");
        server.verify();
    }

    @Test
    void rejectsARedirectThatPointsAtAPrivateAddress() {
        String start = "http://203.0.113.10/start";
        server.expect(requestTo(start)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FOUND).location(URI.create("http://169.254.169.254/latest/meta-data/")));

        assertThatThrownBy(() -> fetcher.fetch(start, MAX_BYTES))
                .isInstanceOf(PermanentJobException.class);
    }

    @Test
    void stopsFollowingAfterTooManyRedirects() {
        String url0 = "http://203.0.113.10/hop0";
        String url1 = "http://203.0.113.10/hop1";
        String url2 = "http://203.0.113.10/hop2";
        String url3 = "http://203.0.113.10/hop3";
        String url4 = "http://203.0.113.10/hop4";
        server.expect(requestTo(url0)).andRespond(withStatus(HttpStatus.FOUND).location(URI.create(url1)));
        server.expect(requestTo(url1)).andRespond(withStatus(HttpStatus.FOUND).location(URI.create(url2)));
        server.expect(requestTo(url2)).andRespond(withStatus(HttpStatus.FOUND).location(URI.create(url3)));
        server.expect(requestTo(url3)).andRespond(withStatus(HttpStatus.FOUND).location(URI.create(url4)));

        assertThatThrownBy(() -> fetcher.fetch(url0, MAX_BYTES))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void serverErrorIsRetryable() {
        String url = "http://203.0.113.10/flaky";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fetcher.fetch(url, MAX_BYTES))
                .isInstanceOf(RetryableJobException.class);
    }
}
