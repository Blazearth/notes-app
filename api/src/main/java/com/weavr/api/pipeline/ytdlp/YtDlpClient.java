package com.weavr.api.pipeline.ytdlp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.weavr.api.pipeline.ExternalProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The free half of the extraction cascade: metadata and platform captions.
 *
 * <p><b>No video is downloaded here.</b> Both calls pass {@code --skip-download};
 * the probe writes nothing to disk at all. In the common case — a YouTube Short
 * with auto-captions, or a Reel with a real description — the cascade ends
 * without a byte of media transferred, which is the entire reason the steps are
 * ordered this way.
 */
@Component
public class YtDlpClient {

    private static final Logger log = LoggerFactory.getLogger(YtDlpClient.class);

    private final ExternalProcess processes;
    private final ObjectMapper objectMapper;
    private final YtDlpProperties properties;

    YtDlpClient(ExternalProcess processes, ObjectMapper objectMapper, YtDlpProperties properties) {
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Step 2 of the cascade, run first because it is strictly cheaper: one
     * request, no files, and its answer tells us whether step 1 is even
     * available (CA#14 — caption-language discovery from {@code subtitles} and
     * {@code automatic_captions}).
     */
    public SourceMetadata probe(String url) {
        ExternalProcess.Result result = processes.run(List.of(
                properties.binary(),
                "--dump-single-json",
                "--skip-download",
                "--no-playlist",
                "--no-warnings",
                url), properties.probeTimeout());

        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }

        JsonNode json = objectMapper.readTree(result.stdout());
        return new SourceMetadata(
                text(json, "id"),
                text(json, "title"),
                text(json, "description"),
                text(json, "uploader"),
                json.path("duration").isNumber() ? json.path("duration").asDouble() : null,
                text(json, "thumbnail"),
                languageKeys(json.path("subtitles")),
                languageKeys(json.path("automatic_captions")));
    }

    /**
     * Step 1 of the cascade: real subtitles if the uploader provided them,
     * machine captions otherwise. Free, and covers most of YouTube plus many
     * TikToks and Reels — a primary path, not a fallback.
     *
     * <p>Subtitles have to land on disk (yt-dlp has no "print the subs" mode), so
     * this works in a caller-supplied temp directory and reads them back. The
     * caller owns cleanup.
     */
    public Optional<String> fetchCaptions(String url, Path workDir) {
        ExternalProcess.Result result = processes.run(List.of(
                properties.binary(),
                "--skip-download",
                "--write-subs",
                "--write-auto-subs",
                "--sub-langs", properties.subtitleLangs(),
                "--sub-format", "vtt",
                "--convert-subs", "vtt",
                "--no-playlist",
                "--no-warnings",
                "-o", "%(id)s.%(ext)s",
                url), properties.captionTimeout(), workDir);

        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }

        String vtt = readLargestVtt(workDir);
        if (vtt == null) {
            return Optional.empty();
        }

        String text = VttParser.toPlainText(vtt);
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * A video can yield several subtitle files (uploaded plus auto-generated,
     * or several English variants). The largest is the most complete; picking by
     * filename would mean guessing at yt-dlp's language suffixes.
     */
    private String readLargestVtt(Path workDir) {
        try (Stream<Path> files = Files.list(workDir)) {
            Optional<Path> largest = files
                    .filter(p -> p.getFileName().toString().endsWith(".vtt"))
                    .max(Comparator.comparingLong(YtDlpClient::sizeOf));

            if (largest.isEmpty()) {
                return null;
            }
            return Files.readString(largest.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read subtitles from {}", workDir, e);
            return null;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static List<String> languageKeys(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return List.copyOf(keys);
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private static String describe(ExternalProcess.Result result) {
        if (result.timedOut()) {
            return "yt-dlp timed out";
        }
        return "yt-dlp exited " + result.exitCode();
    }
}
