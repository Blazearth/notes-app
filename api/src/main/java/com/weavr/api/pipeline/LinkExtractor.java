package com.weavr.api.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import com.weavr.api.job.RetryableJobException;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Readable-text extraction for a plain link — step 4 of the cascade's
 * non-video half, reached when the probe finds no yt-dlp extractor for the
 * URL at all (a news article, a blog post, anything that is not a media
 * platform).
 */
@Component
public class LinkExtractor {

    private final RestClient http;

    LinkExtractor(RestClient.Builder restClientBuilder) {
        // A default browser-like UA: some sites 403 a bare Java HTTP client.
        this.http = restClientBuilder
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (compatible; WeavrBot/1.0; +https://weavr.app)")
                .build();
    }

    public record LinkExtraction(String title, String text) {
    }

    public Optional<LinkExtraction> extract(String url) {
        byte[] html = fetch(url);
        if (html.length == 0) {
            return Optional.empty();
        }
        return parse(url, html);
    }

    /**
     * Bytes in, not a {@code String}: an arbitrary web page's declared
     * charset lives in its HTTP header <em>or</em> a {@code <meta>} tag
     * inside the document, and guessing wrong is exactly the mojibake bug
     * GeminiClient hit. Jsoup's own sniffing (passing a {@code null}
     * charset) handles both cases; a manually decoded {@code String} would
     * have already committed to a charset before Jsoup ever saw the bytes.
     */
    Optional<LinkExtraction> parse(String url, byte[] html) {
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(html), null, url);
        } catch (IOException e) {
            return Optional.empty();
        }

        Article article = new Readability4J(url, document).parse();
        String text = article.getTextContent();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String title = article.getTitle();
        return Optional.of(new LinkExtraction(title == null ? "" : title.strip(), text.strip()));
    }

    private byte[] fetch(String url) {
        try {
            byte[] bytes = http.get().uri(url).retrieve().body(byte[].class);
            return bytes == null ? new byte[0] : bytes;
        } catch (Exception e) {
            throw new RetryableJobException("Could not fetch link: " + e.getMessage(), e);
        }
    }
}
