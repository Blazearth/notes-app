package com.weavr.api.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpErrors;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the text blob for a save, cheapest source first.
 *
 * <p>The ordering is the whole design. Never send video or audio to a model —
 * the worker's job is to end up with text, and to stop at the first source that
 * produces usable text:
 *
 * <ol>
 *   <li><b>Metadata probe</b> — one yt-dlp call, no files, no media. Title,
 *       description and uploader are frequently enough on their own, and the same
 *       response reports which caption tracks exist.</li>
 *   <li><b>Platform captions</b> — free, zero model requests, covers most of
 *       YouTube and many Reels and TikToks. A primary path, not a fallback.</li>
 *   <li><b>ASR</b> — not implemented. See the note below.</li>
 * </ol>
 *
 * <p>Nothing here downloads video, so nothing here needs cleaning up beyond the
 * subtitle temp directory — which is deleted in a {@code finally}, because a
 * small instance's ephemeral disk is what fills first.
 */
@Component
public class ExtractionCascade {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCascade.class);

    /** Below this, metadata alone is not worth calling an extraction. */
    private static final int USABLE_TEXT_THRESHOLD = 40;

    private final YtDlpClient ytDlp;

    ExtractionCascade(YtDlpClient ytDlp) {
        this.ytDlp = ytDlp;
    }

    /**
     * @param text     the assembled blob, ready for the model
     * @param source   which cascade step produced it
     * @param metadata the probe result, kept for enrichment and the thumbnail
     */
    public record Extraction(String text, String source, SourceMetadata metadata) {
    }

    public Extraction extractFromUrl(String url) {
        SourceMetadata metadata = probe(url);

        // Captions first when the probe says they exist: they carry the actual
        // spoken content, where a description only sometimes does.
        if (metadata.hasCaptions()) {
            Optional<String> captions = fetchCaptions(url);
            if (captions.isPresent() && captions.get().length() >= USABLE_TEXT_THRESHOLD) {
                return new Extraction(combine(captions.get(), metadata), "captions", metadata);
            }
            log.debug("Captions were advertised for {} but produced nothing usable", url);
        }

        String metadataText = metadata.asText();
        if (metadataText.length() >= USABLE_TEXT_THRESHOLD) {
            return new Extraction(metadataText, "metadata", metadata);
        }

        // Step 3 would be ffmpeg -> 16 kHz mono -> a hosted Whisper endpoint.
        // Deliberately absent: it is a different provider with its own key and
        // its own failure modes, and it is only reached when both free sources
        // come up empty. Failing loudly here beats silently handing the model a
        // title and calling it a transcript.
        throw new PermanentJobException("no_text_extracted",
                "Weavr couldn't find any text in that post to work with.");
    }

    private SourceMetadata probe(String url) {
        try {
            return ytDlp.probe(url);
        } catch (YtDlpFailedException e) {
            throw translate(e);
        } catch (ProcessExecutionException e) {
            // yt-dlp missing from PATH is a deployment fault, not the save's.
            // Retryable so the work survives a container that is rebuilt with it.
            throw new RetryableJobException("yt-dlp is not runnable: " + e.getMessage(), e);
        }
    }

    private Optional<String> fetchCaptions(String url) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-subs-");
            return ytDlp.fetchCaptions(url, workDir);
        } catch (IOException e) {
            throw new RetryableJobException("Could not create a temp directory for subtitles", e);
        } catch (YtDlpFailedException e) {
            // A caption fetch that fails is not fatal — metadata may still carry
            // the save. Fall through rather than failing the whole extraction.
            log.debug("Caption fetch failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (ProcessExecutionException e) {
            throw new RetryableJobException("yt-dlp is not runnable: " + e.getMessage(), e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** Captions carry the speech; the title and uploader give the model context. */
    private static String combine(String captions, SourceMetadata metadata) {
        String header = metadata.asText();
        return header.isBlank() ? captions : header + "\n\n" + captions;
    }

    private static RuntimeException translate(YtDlpFailedException e) {
        YtDlpErrors.Classification classification = YtDlpErrors.classify(e.stderr());
        if (classification.permanent()) {
            return new PermanentJobException(classification.errorCode(), classification.userMessage(), e);
        }
        return new RetryableJobException(
                classification.errorCode() + ": " + classification.userMessage(), e);
    }

    /**
     * Storage cost, legal exposure and a ~1 GB free-tier ceiling all argue the
     * same way: nothing from a temp dir survives the job.
     */
    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; the OS reclaims temp space regardless.
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean temp directory {}", directory, e);
        }
    }

    /** Stage payload for {@code save_stages}, kept small — it is a breadcrumb. */
    public static Map<String, Object> stagePayload(Extraction extraction) {
        SourceMetadata metadata = extraction.metadata();
        return Map.of(
                "source", extraction.source(),
                "textLength", extraction.text().length(),
                "title", metadata.title() == null ? "" : metadata.title(),
                "uploader", metadata.uploader() == null ? "" : metadata.uploader(),
                "hasCaptions", metadata.hasCaptions());
    }
}
