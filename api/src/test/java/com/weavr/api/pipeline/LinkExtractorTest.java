package com.weavr.api.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the deterministic HTML-to-article parsing directly (package-visible
 * {@code parse}) rather than the network fetch — a live page is what
 * {@code YtDlpLiveTest}'s opt-in pattern is for, not a unit test.
 */
class LinkExtractorTest {

    private final LinkExtractor extractor = new LinkExtractor(RestClient.builder());

    @Test
    void extractsTheArticleBodyAndTitleFromCleanHtml() {
        String html = """
                <html>
                <head><title>Page chrome title</title></head>
                <body>
                  <nav>Home | About | Contact</nav>
                  <article>
                    <h1>10 Rules for Writing Great Software</h1>
                    <p>Make it work, make it right, make it fast. That is the whole discipline
                    in three phrases, and most teams try to skip straight to fast.</p>
                    <p>Premature optimization is the root of all evil, or at least a great
                    deal of it, and it is worth remembering before reaching for a cache.</p>
                  </article>
                  <footer>Copyright 2026</footer>
                </body>
                </html>
                """;

        Optional<LinkExtractor.LinkExtraction> result =
                extractor.parse("https://example.com/article", html.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isPresent();
        assertThat(result.get().text())
                .contains("Premature optimization")
                .doesNotContain("Home | About | Contact");
        // Readability4J prefers the <title> tag over an in-body heading —
        // this only pins that a title comes through at all, not which one.
        assertThat(result.get().title()).isNotBlank();
    }

    @Test
    void decodesNonAsciiHtmlCorrectlyViaJsoupsOwnCharsetSniffing() {
        // No Content-Type charset is passed here at all (parse() only ever
        // sees bytes) — the same charset trap that hit GeminiClient, avoided
        // by handing raw bytes to Jsoup instead of a manually-decoded String.
        String html = """
                <html>
                <head><meta charset="utf-8"><title>Café review</title></head>
                <body>
                  <article>
                    <h1>Café review</h1>
                    <p>The café société down the street serves a genuinely excellent
                    déjà vu blend, worth the walk on a cold morning.</p>
                  </article>
                </body>
                </html>
                """;

        Optional<LinkExtractor.LinkExtraction> result =
                extractor.parse("https://example.com/cafe", html.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isPresent();
        assertThat(result.get().text()).contains("café société", "déjà vu");
    }

    /**
     * A JS-rendered SPA shell: yt-dlp never sees this case (it is not a video
     * URL), and there is no server-rendered text at all for Readability to
     * find — the realistic "nothing here" case, as opposed to a page with
     * some genuine but short text (which the cascade's own length threshold
     * rejects, not this parse step).
     */
    @Test
    void returnsEmptyWhenThePageHasNoTextAtAll() {
        String html = "<html><head><title></title></head><body><div id=\"app\"></div></body></html>";

        Optional<LinkExtractor.LinkExtraction> result =
                extractor.parse("https://example.com/spa", html.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEmpty();
    }
}
