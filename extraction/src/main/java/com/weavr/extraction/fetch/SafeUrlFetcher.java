package com.weavr.extraction.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches a URL the caller supplied — never trust it as merely "some link",
 * because {@code fetch()} makes a server-side HTTP request to wherever it
 * points. Ported from {@code api}'s {@code SafeUrlFetcher} (CLAUDE.md F2/F3),
 * unchanged except for the exception type it raises.
 *
 * <p>Three defences, all measured against the failure they close:
 * <ul>
 *   <li><b>SSRF</b> — the host is resolved and every resolved address checked
 *   against loopback/link-local/private/unique-local/multicast/unspecified
 *   <em>before</em> any request is made, and again on every redirect hop, so
 *   a URL that only becomes unsafe after a redirect is still caught. This
 *   closes the obvious case (a URL whose DNS record — or literal IP — points
 *   inside); it does not close a DNS-rebinding attack that changes the
 *   record's answer between this check and the connection a moment later —
 *   that needs pinning the actual socket to the address that was validated
 *   (docs/extraction-architecture.md Part D Security, still open).</li>
 *   <li><b>Unbounded reads</b> — the response body is read in a bounded loop
 *   that aborts the moment {@code maxBytes} is crossed, so a large or
 *   deliberately slow response cannot buffer past the ceiling first and get
 *   checked after the fact.</li>
 *   <li><b>Redirects</b> — followed manually, capped, and re-validated per
 *   hop, rather than left to the HTTP client's own (unvalidated) redirect
 *   handling.</li>
 * </ul>
 */
@Component
public class SafeUrlFetcher {

    private static final int MAX_REDIRECTS = 3;

    private final RestClient http;

    SafeUrlFetcher(RestClient.Builder restClientBuilder) {
        // A default browser-like UA: some sites 403 a bare Java HTTP client.
        this.http = restClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; WeavrBot/1.0; +https://weavr.app)")
                .build();
    }

    /**
     * @param maxBytes response bodies larger than this abort mid-stream and
     *                 fail permanently — retrying will not shrink the page
     */
    public byte[] fetch(String url, long maxBytes) {
        URI uri = parse(url);
        try {
            return fetchFollowingRedirects(uri, maxBytes, MAX_REDIRECTS);
        } catch (ExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionException(ErrorCode.NETWORK_ERROR, "Could not fetch " + url + ": " + e.getMessage(), e);
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                throw new ExtractionException(ErrorCode.INVALID_URL, "That link isn't a valid URL.");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "That link isn't a valid URL.", e);
        }
    }

    private byte[] fetchFollowingRedirects(URI uri, long maxBytes, int redirectsLeft) throws IOException {
        requireSafe(uri);

        FetchOutcome outcome = http.get().uri(uri).exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (status >= 300 && status < 400) {
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                return location == null || location.isBlank()
                        ? FetchOutcome.done(new byte[0])
                        : FetchOutcome.redirect(uri.resolve(location));
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                return FetchOutcome.failed(status);
            }
            try {
                return FetchOutcome.done(readBounded(response.getBody(), maxBytes));
            } catch (ResponseTooLargeException e) {
                return FetchOutcome.oversized();
            }
        });

        if (outcome.tooLarge) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "That content is too large for Weavr to read.");
        }
        if (outcome.failedStatus != null) {
            throw new IOException("Unexpected response status " + outcome.failedStatus);
        }
        if (outcome.location != null) {
            if (redirectsLeft <= 0) {
                throw new ExtractionException(ErrorCode.INVALID_URL, "That link redirects too many times.");
            }
            return fetchFollowingRedirects(outcome.location, maxBytes, redirectsLeft - 1);
        }
        return outcome.bytes;
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new ResponseTooLargeException();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void requireSafe(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "Weavr can only fetch http/https links.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "That link isn't a valid URL.");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ExtractionException(ErrorCode.INVALID_URL, "Weavr couldn't resolve that link.", e);
        }
        for (InetAddress address : resolved) {
            if (isDisallowed(address)) {
                throw new ExtractionException(ErrorCode.INVALID_URL,
                        "That link points somewhere Weavr won't fetch from.");
            }
        }
    }

    private static boolean isDisallowed(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique local (fc00::/7) — InetAddress#isSiteLocalAddress only
        // recognises the deprecated fec0::/10 range, not this one.
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static final class ResponseTooLargeException extends RuntimeException {
    }

    private record FetchOutcome(byte[] bytes, URI location, boolean tooLarge, Integer failedStatus) {
        static FetchOutcome done(byte[] bytes) {
            return new FetchOutcome(bytes, null, false, null);
        }

        static FetchOutcome redirect(URI location) {
            return new FetchOutcome(null, location, false, null);
        }

        static FetchOutcome oversized() {
            return new FetchOutcome(null, null, true, null);
        }

        static FetchOutcome failed(int status) {
            return new FetchOutcome(null, null, false, status);
        }
    }
}
