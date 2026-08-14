package com.weavr.extraction.link;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import com.weavr.extraction.fetch.SafeUrlFetcher;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

/**
 * Readable-text extraction for a plain link — reached when the probe finds no
 * yt-dlp extractor for the URL at all (a news article, a blog post, anything
 * that is not a media platform). Ported unchanged from {@code api}.
 */
@Component
public class LinkExtractor {

    /** Generous for an article page; {@link SafeUrlFetcher} aborts the stream at this ceiling. */
    private static final long MAX_HTML_BYTES = 5 * 1024 * 1024;

    private final SafeUrlFetcher fetcher;

    LinkExtractor(SafeUrlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public record LinkExtraction(String title, String text) {
    }

    public Optional<LinkExtraction> extract(String url) {
        byte[] html = fetcher.fetch(url, MAX_HTML_BYTES);
        if (html.length == 0) {
            return Optional.empty();
        }
        return parse(url, html);
    }

    /**
     * Bytes in, not a {@code String}: an arbitrary web page's declared
     * charset lives in its HTTP header <em>or</em> a {@code <meta>} tag
     * inside the document, and guessing wrong is exactly the mojibake bug
     * this project has hit before. Jsoup's own sniffing (passing a
     * {@code null} charset) handles both cases.
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
}
