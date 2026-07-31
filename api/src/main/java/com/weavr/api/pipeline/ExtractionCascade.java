package com.weavr.api.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.audio.AsrTranscriber;
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
 *   <li><b>ASR</b> — audio only, bounded, a different provider (Groq Whisper),
 *       reached only when both free sources above come up empty.</li>
 *   <li><b>Readable-text / PDF</b> — a parallel branch, not a further fallback:
 *       reached when the probe finds no yt-dlp extractor at all (a plain
 *       article) or the URL is obviously a PDF, in which case none of the
 *       video-platform steps above apply.</li>
 * </ol>
 *
 * <p>Nothing here keeps a downloaded byte longer than the call that needed it —
 * every temp directory is deleted in a {@code finally} ({@link TempDirs}),
 * because a small instance's ephemeral disk is what fills first.
 */
@Component
public class ExtractionCascade {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCascade.class);

    /** Below this, metadata alone is not worth calling an extraction. */
    private static final int USABLE_TEXT_THRESHOLD = 40;

    private final YtDlpClient ytDlp;
    private final AsrTranscriber asr;
    private final LinkExtractor linkExtractor;
    private final PdfExtractor pdfExtractor;

    ExtractionCascade(YtDlpClient ytDlp, AsrTranscriber asr,
                      LinkExtractor linkExtractor, PdfExtractor pdfExtractor) {
        this.ytDlp = ytDlp;
        this.asr = asr;
        this.linkExtractor = linkExtractor;
        this.pdfExtractor = pdfExtractor;
    }

    /**
     * @param text     the assembled blob, ready for the model
     * @param source   which cascade step produced it
     * @param metadata the probe result, kept for enrichment and the thumbnail
     */
    public record Extraction(String text, String source, SourceMetadata metadata) {
    }

    public Extraction extractFromUrl(String url) {
        if (looksLikePdf(url)) {
            return extractPdf(url);
        }

        SourceMetadata metadata;
        try {
            metadata = probe(url);
        } catch (PermanentJobException e) {
            // Not a video platform at all — a plain article link. This is a
            // different branch, not a further fallback: none of the
            // caption/metadata/ASR steps below apply to a page yt-dlp has no
            // extractor for.
            if ("unsupported_source".equals(e.errorCode())) {
                return extractLink(url);
            }
            throw e;
        }

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

        // Last resort: audio only, bounded, a different provider (Groq
        // Whisper) — reached only when both free sources above came up empty.
        Optional<String> transcript = asr.transcribe(url);
        if (transcript.isPresent() && transcript.get().length() >= USABLE_TEXT_THRESHOLD) {
            return new Extraction(combine(transcript.get(), metadata), "asr", metadata);
        }

        throw new PermanentJobException("no_text_extracted",
                "Weavr couldn't find any text in that post to work with.");
    }

    private SourceMetadata probe(String url) {
        try {
            return ytDlp.probe(url);
        } catch (YtDlpFailedException e) {
            throw YtDlpErrors.toException(e);
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
            TempDirs.deleteQuietly(workDir);
        }
    }

    private Extraction extractLink(String url) {
        Optional<LinkExtractor.LinkExtraction> extraction = linkExtractor.extract(url);
        if (extraction.isPresent() && extraction.get().text().length() >= USABLE_TEXT_THRESHOLD) {
            LinkExtractor.LinkExtraction le = extraction.get();
            SourceMetadata metadata = emptyMetadata(le.title());
            return new Extraction(le.text(), "link", metadata);
        }
        throw new PermanentJobException("no_text_extracted",
                "Weavr couldn't find any readable text at that link.");
    }

    private Extraction extractPdf(String url) {
        Optional<String> text = pdfExtractor.extract(url);
        if (text.isPresent() && text.get().length() >= USABLE_TEXT_THRESHOLD) {
            return new Extraction(text.get(), "pdf", emptyMetadata(null));
        }
        throw new PermanentJobException("no_text_extracted",
                "Weavr couldn't find any readable text in that PDF.");
    }

    /** Neither a plain link nor a PDF has a yt-dlp probe result behind it. */
    private static SourceMetadata emptyMetadata(String title) {
        return new SourceMetadata(null, title, null, null, null, null, List.of(), List.of());
    }

    private static boolean looksLikePdf(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        return path.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** Captions carry the speech; the title and uploader give the model context. */
    private static String combine(String captions, SourceMetadata metadata) {
        String header = metadata.asText();
        return header.isBlank() ? captions : header + "\n\n" + captions;
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
