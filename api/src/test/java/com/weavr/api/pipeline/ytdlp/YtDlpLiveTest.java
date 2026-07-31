package com.weavr.api.pipeline.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import com.weavr.api.pipeline.ExternalProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test here that talks to a real yt-dlp and a real network.
 *
 * <p><b>Opt-in.</b> Set {@code WEAVR_LIVE_YTDLP=1} to run it. Everything else in
 * the suite mocks {@link ExternalProcess}, which proves the parsing and the
 * ordering but says nothing about whether the argument lists are accepted, the
 * JSON field names are right, or the subtitle files land where we look for them.
 * Those were all wrong at least once, and only running the binary found it.
 *
 * <p>Excluded from CI on purpose: it depends on a third-party site that
 * rate-limits, changes its markup, and blocks datacenter IPs harder than
 * residential ones. A red build caused by YouTube is worse than no signal.
 *
 * <pre>
 *   WEAVR_LIVE_YTDLP=1 ./mvnw test -Dtest=YtDlpLiveTest
 *   WEAVR_LIVE_YTDLP=1 WEAVR_LIVE_URL=https://... ./mvnw test -Dtest=YtDlpLiveTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WEAVR_LIVE_YTDLP", matches = "1")
class YtDlpLiveTest {

    /** A long-lived TED talk: uploaded English subtitles plus machine captions. */
    private static final String DEFAULT_URL = "https://www.youtube.com/watch?v=8S0FDjFBj8o";

    private final YtDlpClient client = new YtDlpClient(
            new ExternalProcess(),
            new ObjectMapper(),
            new YtDlpProperties(
                    System.getenv().getOrDefault("WEAVR_YTDLP_BINARY", "yt-dlp"),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(120),
                    null));

    private static String url() {
        return System.getenv().getOrDefault("WEAVR_LIVE_URL", DEFAULT_URL);
    }

    /**
     * Every field {@link SourceMetadata} reads, against output we did not write.
     * Field names are the fragile part: {@code uploader}, {@code thumbnail} and
     * the two caption maps are all keys that yt-dlp could rename.
     */
    @Test
    void probeReturnsUsableMetadata() {
        SourceMetadata metadata = client.probe(url());

        System.out.printf("probe: id=%s title=%s uploader=%s duration=%s subs=%d auto=%d%n",
                metadata.id(), metadata.title(), metadata.uploader(), metadata.durationSeconds(),
                metadata.captionLanguages().size(), metadata.autoCaptionLanguages().size());

        assertThat(metadata.id()).isNotBlank();
        assertThat(metadata.title()).isNotBlank();
        assertThat(metadata.asText()).isNotBlank();
    }

    /**
     * The whole caption path: process plumbing, the {@code --sub-langs} value,
     * where the files land, and the VTT parse.
     *
     * <p>Also asserts the fetch stays <em>narrow</em>. `en.*` looks like a
     * sensible default and is not: yt-dlp expands it across the synthesised
     * {@code <source>-<target>} translation tracks, which turned one fetch into
     * 29 downloads and an HTTP 429. A handful of files means the pattern is
     * still exact.
     */
    @Test
    void fetchesAndParsesRealCaptions() throws Exception {
        Path workDir = Files.createTempDirectory("weavr-live-subs-");
        try {
            Optional<String> captions = client.fetchCaptions(url(), workDir);

            long files;
            try (Stream<Path> written = Files.list(workDir)) {
                files = written.count();
            }
            System.out.printf("captions: files=%d present=%s chars=%d%n",
                    files, captions.isPresent(), captions.map(String::length).orElse(0));

            assertThat(files)
                    .as("a narrow --sub-langs; a regex would pull in dozens of translations")
                    .isLessThanOrEqualTo(4);

            captions.ifPresent(text -> {
                assertThat(text).isNotBlank();
                // The parser's real job: no timestamps, no inline karaoke tags.
                assertThat(text).doesNotContain("-->").doesNotContain("<c>");
                System.out.println("first 200: "
                        + text.substring(0, Math.min(200, text.length())));
            });
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void deleteRecursively(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best effort; the OS reclaims temp space regardless.
                }
            });
        }
    }
}
