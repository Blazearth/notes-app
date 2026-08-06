package com.weavr.api.pipeline.ytdlp;

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

import com.weavr.api.pipeline.ExternalProcess;
import jakarta.annotation.PostConstruct;
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
        log.info("yt-dlp: cookiesBase64 configured={}", (b64 != null && !b64.isBlank()));
        if (b64 == null || b64.isBlank()) {
            log.info("yt-dlp: no cookies configured (WEAVR_YTDLP_COOKIES_BASE64 not set)");
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(b64.strip());
            cookiesFile = Files.createTempFile("yt-dlp-cookies-", ".txt");
            cookiesFile.toFile().deleteOnExit();
            Files.write(cookiesFile, decoded);
            // Verify file is readable
            boolean exists = Files.exists(cookiesFile);
            boolean readable = Files.isReadable(cookiesFile);
            long size = exists ? Files.size(cookiesFile) : 0;
            log.info("yt-dlp: cookies written to {} (exists={}, readable={}, size={} bytes)", 
                    cookiesFile, exists, readable, size);
        } catch (Exception e) {
            log.warn("yt-dlp: failed to decode cookies — proceeding without them", e);
            cookiesFile = null;
        }
    }

    /**
     * Prepends {@code --cookies <path>} (if configured) and
     * {@code --extractor-args youtube:player_client=...} to the given command.
     *
     * <p>Both after the binary name, before the rest of the args. The player-client
     * override matters independently of cookies: {@code web} needs a PO token to
     * pass the bot check that cookies alone don't provide, where {@code tv} and
     * {@code android} use a flow that doesn't ask for one.
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

    /**
     * Step 2 of the cascade, run first because it is strictly cheaper: one
     * request, no files, and its answer tells us whether step 1 is even
     * available (CA#14 — caption-language discovery from {@code subtitles} and
     * {@code automatic_captions}).
     */
    public SourceMetadata probe(String url) {
        // --write-comments piggybacks on the same single call rather than a
        // second request. Capped at 20 top-level comments (max_comments,
        // max_parents,max_replies,max_replies_per_thread) — pinned comments are
        // always returned first regardless of sort, so a small cap still finds
        // one reliably while bounding cost on a viral video's thousands of
        // comments. Measured against a real video: +1.1s over the bare probe,
        // and the capped call is indistinguishable in time from no comments at
        // all (~4.4s either way).
        List<String> cmd = withCookies(List.of(
                properties.binary(),
                "--dump-single-json",
                "--skip-download",
                "--write-comments",
                "--extractor-args", "youtube:max_comments=20,20,0,0",
                "--no-playlist",
                "--no-warnings",
                url));
        log.info("yt-dlp probe command: {}", cmd);
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

    /**
     * yt-dlp exposes at most one pinned comment per video (YouTube's own
     * limit), flagged {@code is_pinned: true} in the {@code comments} array —
     * confirmed against a real video, field name and shape both. Not every
     * source has one; comments can be off, or nothing pinned.
     */
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
     * Step 1 of the cascade: real subtitles if the uploader provided them,
     * machine captions otherwise. Free, and covers most of YouTube plus many
     * TikToks and Reels — a primary path, not a fallback.
     *
     * <p>Subtitles have to land on disk (yt-dlp has no "print the subs" mode), so
     * this works in a caller-supplied temp directory and reads them back. The
     * caller owns cleanup.
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
        // already landed on disk — a 429 on the second language would otherwise
        // throw away a complete transcript we had successfully fetched.
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
     * A video can yield several subtitle files — an uploaded track plus a
     * machine-generated one, typically.
     *
     * <p><b>Ranked by extracted prose, not by file size.</b> Auto-generated VTT
     * is inflated several-fold by the rolling window and inline karaoke timings:
     * on one real TED talk the auto track was 43,947 bytes against the uploaded
     * track's 8,456, yet yielded <em>fewer</em> words once parsed (4,284 chars
     * against 4,443). Choosing on raw bytes therefore picks the noisier source
     * systematically, and would prefer a non-Latin translation over English
     * outright, since UTF-8 makes that text larger for the same content.
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
     * Step 3's download: audio only, never the video track (CLAUDE.md — "for
     * ASR, download audio only, never the video"), and bounded by
     * {@code --download-sections} so a long-form video costs the same as a
     * short one. Called only after the probe has already confirmed this URL
     * matches a yt-dlp extractor, so a failure here is "this specific video
     * has no audio / is blocked", not "unsupported site".
     *
     * <p>Like captions, the file has to land on disk — yt-dlp has no "pipe the
     * audio" mode without a shell pipeline, and audio at a 90-second cap is
     * small enough that a temp file costs nothing worth avoiding. The caller
     * owns cleanup.
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
        // Succeeded but wrote nothing: no audio stream on this source.
        return Optional.empty();
    }

    /**
     * The OCR tier's download — the only path in the cascade that fetches video
     * at all, and it does so grudgingly.
     *
     * <p>Reached only when captions, metadata and ASR have all come up empty,
     * which in practice means the post's payload is burned into the pixels. Two
     * bounds keep that affordable: the <em>worst</em> stream that still has
     * legible text ({@code worst[height>=360]}, an order of magnitude smaller
     * than the default) and the same duration cap the audio path uses.
     *
     * <p>The file is deleted with the caller's temp directory as soon as frames
     * have been cut from it. Nothing video-shaped is ever persisted.
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

        // Same reasoning as captions and audio: read the directory before
        // judging the exit code. yt-dlp can exit non-zero over a fragment it
        // gave up on while a perfectly usable file already sits on disk.
        Optional<Path> video = largestFile(workDir);
        if (video.isPresent()) {
            return video;
        }
        if (!result.succeeded()) {
            throw new YtDlpFailedException(describe(result), result.stderr());
        }
        return Optional.empty();
    }

    /** The audio file's extension depends on the source's best format, so pick by presence, not name. */
    private Optional<Path> largestFile(Path workDir) {
        try (Stream<Path> files = Files.list(workDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(YtDlpClient::sizeQuietly));
        } catch (IOException e) {
            log.warn("Could not read audio download from {}", workDir, e);
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
