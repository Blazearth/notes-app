package com.weavr.extraction.ytdlp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.weavr.extraction.process.ExternalProcess;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The free half of the extraction cascade: metadata and platform captions.
 * Ported unchanged from {@code api}'s {@code YtDlpClient} (docs/extraction-architecture.md
 * Phase 3).
 *
 * <p><b>No video is downloaded here.</b> Both calls pass {@code --skip-download};
 * the probe writes nothing to disk at all.
 */
@Component
public class YtDlpClient {

    private static final Logger log = LoggerFactory.getLogger(YtDlpClient.class);

    private final ExternalProcess processes;
    private final ObjectMapper objectMapper;
    private final YtDlpProperties properties;

    /** Written once at startup from {@code cookiesBase64}; null when not configured. */
    private Path cookiesFile;

    YtDlpClient(ExternalProcess processes, ObjectMapper objectMapper, YtDlpProperties properties) {
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    void initCookies() {
        String b64 = properties.cookiesBase64();
        if (b64 == null || b64.isBlank()) {
            log.info("yt-dlp: no cookies configured (WEAVR_YTDLP_COOKIES_BASE64 not set)");
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(b64.strip());
            cookiesFile = Files.createTempFile("yt-dlp-cookies-", ".txt");
            cookiesFile.toFile().deleteOnExit();
            Files.write(cookiesFile, decoded);
            log.info("yt-dlp: cookies written to {} ({} bytes)", cookiesFile, decoded.length);
        } catch (Exception e) {
            log.warn("yt-dlp: failed to decode cookies — proceeding without them", e);
            cookiesFile = null;
        }
    }

    /**
     * Prepends {@code --cookies <path>} (if configured) and
     * {@code --extractor-args youtube:player_client=...} to the given command.
     */
    private List<String> withCookies(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd.size() + 4);
        out.add(cmd.get(0));
        if (cookiesFile != null) {
            out.add("--cookies");
            out.add(cookiesFile.toString());
        }
        out.add("--extractor-args");
        out.add("youtube:player_client=" + properties.playerClients());
        out.addAll(cmd.subList(1, cmd.size()));
        return out;
    }

    public SourceMetadata probe(String url) {
        List<String> cmd = withCookies(List.of(
                properties.binary(),
                "--dump-single-json",
                "--skip-download",
                "--write-comments",
                "--extractor-args", "youtube:max_comments=20,20,0,0",
                "--no-playlist",
                "--no-warnings",
                url));
        ExternalProcess.Result result = processes.run(cmd, properties.probeTimeout());

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
                languageKeys(json.path("automatic_captions")),
                pinnedComment(json.path("comments")));
    }

    private static String pinnedComment(JsonNode comments) {
        if (!comments.isArray()) return null;
        for (JsonNode comment : comments) {
            if (comment.path("is_pinned").asBoolean(false)) {
                return text(comment, "text");
            }
        }
        return null;
    }

    /**
     * Real subtitles if the uploader provided them, machine captions
     * otherwise. Subtitles have to land on disk (yt-dlp has no "print the
     * subs" mode), so this works in a caller-supplied temp directory and
     * reads them back. The caller owns cleanup.
     */
    public Optional<String> fetchCaptions(String url, Path workDir) {
        ExternalProcess.Result result = processes.run(withCookies(List.of(
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
                url)), properties.captionTimeout(), workDir);

        // Read the directory *before* judging the exit code. yt-dlp exits
        // non-zero when any requested track fails, even though earlier tracks
        // already landed on disk.
        Optional<String> captions = bestCaptions(workDir);
        if (captions.isPresent()) {
            if (!result.succeeded()) {
                log.debug("yt-dlp exited {} but usable captions were written; keeping them",
                        result.exitCode());
            }
            return captions;
        }

        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }
        return Optional.empty();
    }

    /**
     * Ranked by extracted prose, not by file size — auto-generated VTT is
     * inflated several-fold by the rolling window and inline karaoke timings.
     */
    private Optional<String> bestCaptions(Path workDir) {
        try (Stream<Path> files = Files.list(workDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".vtt"))
                    .map(YtDlpClient::readQuietly)
                    .filter(Objects::nonNull)
                    .map(VttParser::toPlainText)
                    .filter(text -> !text.isBlank())
                    .max(Comparator.comparingInt(String::length));
        } catch (IOException e) {
            log.warn("Could not read subtitles from {}", workDir, e);
            return Optional.empty();
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read subtitle file {}", path, e);
            return null;
        }
    }

    /**
     * Audio only, never the video track, bounded by {@code --download-sections}.
     * Called only after the probe has already confirmed this URL matches a
     * yt-dlp extractor. The caller owns cleanup of {@code workDir}.
     *
     * @param maxDurationSeconds upper bound on how much of the source is
     *                           downloaded, e.g. {@code 90} for {@code "*0-90"}
     */
    public Optional<Path> downloadAudio(String url, Path workDir, int maxDurationSeconds) {
        ExternalProcess.Result result = processes.run(withCookies(List.of(
                properties.binary(),
                "-f", "bestaudio",
                "--download-sections", "*0-" + maxDurationSeconds,
                "--no-playlist",
                "--no-warnings",
                "-o", "%(id)s.%(ext)s",
                url)), properties.audioTimeout(), workDir);

        Optional<Path> audio = largestFile(workDir);
        if (audio.isPresent()) {
            return audio;
        }
        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }
        return Optional.empty();
    }

    /**
     * The OCR tier's download — the only path in the cascade that fetches
     * video at all, and it does so grudgingly: the <em>worst</em> stream that
     * still has legible text, bounded to the same duration cap the audio path
     * uses. The file is deleted with the caller's temp directory as soon as
     * frames have been cut from it.
     *
     * @return the downloaded file, or empty if the source yielded no video
     */
    public Optional<Path> downloadVideo(String url, Path workDir, int maxDurationSeconds) {
        ExternalProcess.Result result = processes.run(withCookies(List.of(
                properties.binary(),
                "-f", properties.videoFormat(),
                "--download-sections", "*0-" + maxDurationSeconds,
                "--no-playlist",
                "--no-warnings",
                "-o", "%(id)s.%(ext)s",
                url)), properties.videoTimeout(), workDir);

        Optional<Path> video = largestFile(workDir);
        if (video.isPresent()) {
            return video;
        }
        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }
        return Optional.empty();
    }

    private Optional<Path> largestFile(Path workDir) {
        try (Stream<Path> files = Files.list(workDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(YtDlpClient::sizeQuietly));
        } catch (IOException e) {
            log.warn("Could not read download from {}", workDir, e);
            return Optional.empty();
        }
    }

    private static long sizeQuietly(Path path) {
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
