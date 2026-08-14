package com.weavr.extraction.cascade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.weavr.extraction.artifact.ArtifactStore;
import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import com.weavr.extraction.link.LinkExtractor;
import com.weavr.extraction.ocr.OcrProperties;
import com.weavr.extraction.ocr.VisualExtractor;
import com.weavr.extraction.pdf.PdfExtractor;
import com.weavr.extraction.process.ProcessExecutionException;
import com.weavr.extraction.process.TempDirs;
import com.weavr.extraction.ytdlp.RapidYtClient;
import com.weavr.extraction.ytdlp.SourceMetadata;
import com.weavr.extraction.ytdlp.YtDlpClient;
import com.weavr.extraction.ytdlp.YtDlpErrors;
import com.weavr.extraction.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The service's own version of {@code api}'s {@code ExtractionCascade} —
 * cheapest source first, and never send video or audio to a model. This
 * class is the whole reason Phase 3 exists: it is what {@code POST /extract}
 * calls (docs/extraction-architecture.md Part D / Phase 3).
 *
 * <p>Three differences from the monolith's cascade, all deliberate:
 * <ul>
 *   <li><b>ASR and vision are never called here.</b> Both are AI calls with
 *   their own budgets and keys, so they stay in the backend (Part D, "Where
 *   the line sits"). This class returns an audio artifact with
 *   {@code needsTranscription: true}, or frame artifacts with
 *   {@code needsVisionEscalation: true}, and stops.</li>
 *   <li><b>The audio and visual tiers are opt-in</b>, via {@link ExtractionOptions},
 *   rather than always attempted — the doc's own request shape.</li>
 *   <li><b>YouTube's fail-fast decision (F5) is preserved exactly</b>: when
 *   RapidAPI is reachable but both its metadata and every caption track are
 *   too thin, this throws immediately rather than falling through to a
 *   yt-dlp audio/video download aimed at YouTube directly — confirmed dead
 *   from a datacenter IP, and spending a full download on a platform whose
 *   fast path already came up empty is a bad trade regardless.</li>
 * </ul>
 */
@Component
public class ExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ExtractionOrchestrator.class);

    /** Below this, metadata alone is not worth returning as a result. */
    private static final int USABLE_TEXT_THRESHOLD = 40;

    /** Bound on the audio download when the caller doesn't override it — Reels/Shorts are shorter anyway. */
    private static final int DEFAULT_AUDIO_SECONDS = 90;

    private final YtDlpClient ytDlp;
    private final RapidYtClient rapidYt;
    private final LinkExtractor linkExtractor;
    private final PdfExtractor pdfExtractor;
    private final VisualExtractor visual;
    private final ArtifactStore artifacts;
    private final OcrProperties ocrProperties;

    ExtractionOrchestrator(YtDlpClient ytDlp, RapidYtClient rapidYt, LinkExtractor linkExtractor,
                           PdfExtractor pdfExtractor, VisualExtractor visual, ArtifactStore artifacts,
                           OcrProperties ocrProperties) {
        this.ytDlp = ytDlp;
        this.rapidYt = rapidYt;
        this.linkExtractor = linkExtractor;
        this.pdfExtractor = pdfExtractor;
        this.visual = visual;
        this.artifacts = artifacts;
        this.ocrProperties = ocrProperties;
    }

    /**
     * @param prefix groups any stored artifacts under, e.g. the save id
     *               carried in {@code Idempotency-Key} — see
     *               {@link com.weavr.extraction.artifact.ArtifactStore#put}
     */
    public ExtractionResult extract(String url, ExtractionOptions options, String prefix) {
        if (looksLikePdf(url)) {
            return extractPdf(url);
        }

        // --- RapidAPI fast path for YouTube (no bot check, includes transcript) ---
        if (RapidYtClient.extractVideoId(url) != null) {
            Optional<RapidYtClient.ProbeResult> rapid = rapidYt.probe(url);
            if (rapid.isPresent()) {
                RapidYtClient.ProbeResult r = rapid.get();
                log.info("RapidAPI probe succeeded for {}", url);
                if (r.transcript().isPresent() && r.transcript().get().length() >= USABLE_TEXT_THRESHOLD) {
                    return textResult("captions", combine(r.transcript().get(), r.metadata()), "youtube", r.metadata());
                }
                String metaText = r.metadata().asText();
                if (metaText.length() >= USABLE_TEXT_THRESHOLD) {
                    return textResult("metadata", metaText, "youtube", r.metadata());
                }
                // F5, preserved: fail fast rather than falling through to a
                // yt-dlp download aimed straight at YouTube.
                log.info("RapidAPI metadata and every caption track were too thin for {}", url);
                throw new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE,
                        "Weavr couldn't find enough text in that YouTube video to work with.");
            }
            log.warn("RapidAPI probe returned empty for {}, falling back to yt-dlp", url);
        }

        // --- yt-dlp path (non-YouTube, or RapidAPI unavailable/failed) ---
        SourceMetadata metadata;
        try {
            metadata = ytDlp.probe(url);
        } catch (YtDlpFailedException e) {
            ExtractionException mapped = YtDlpErrors.toException(e);
            if (mapped.code() == ErrorCode.UNSUPPORTED_URL) {
                return extractLinkOrThrow(url);
            }
            throw mapped;
        } catch (ProcessExecutionException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR, "yt-dlp is not runnable: " + e.getMessage(), e);
        }

        String platform = Platform.detect(url);

        if (metadata.hasCaptions()) {
            Optional<String> captions = fetchCaptions(url);
            if (captions.isPresent() && captions.get().length() >= USABLE_TEXT_THRESHOLD) {
                return textResult("captions", combine(captions.get(), metadata), platform, metadata);
            }
            log.debug("Captions were advertised for {} but produced nothing usable", url);
        }

        String metadataText = metadata.asText();
        if (metadataText.length() >= USABLE_TEXT_THRESHOLD) {
            return textResult("metadata", metadataText, platform, metadata);
        }

        if (options.audio()) {
            Optional<ArtifactStore.StoredArtifact> audio = downloadAudioArtifact(
                    url, resolvedDuration(options, DEFAULT_AUDIO_SECONDS), prefix);
            if (audio.isPresent()) {
                return new ExtractionResult("audio", "", true, false,
                        ExtractionMetadata.from(platform, metadata), List.of(audio.get()));
            }
        }

        if (options.frames()) {
            Optional<VisualExtractor.VisualText> visualText = visual.extract(
                    url, resolvedDuration(options, ocrProperties.maxVideoSeconds()), prefix);
            if (visualText.isPresent()) {
                VisualExtractor.VisualText v = visualText.get();
                if (!v.text().isBlank() || v.needsVisionEscalation()) {
                    return new ExtractionResult(v.source(), v.text(), false, v.needsVisionEscalation(),
                            ExtractionMetadata.from(platform, metadata), v.frameArtifacts());
                }
            }
        }

        throw new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE,
                "Weavr couldn't find any text in that post to work with.");
    }

    private ExtractionResult extractLinkOrThrow(String url) {
        Optional<LinkExtractor.LinkExtraction> extraction = linkExtractor.extract(url);
        if (extraction.isPresent() && extraction.get().text().length() >= USABLE_TEXT_THRESHOLD) {
            LinkExtractor.LinkExtraction le = extraction.get();
            return new ExtractionResult("link", le.text(), false, false,
                    ExtractionMetadata.empty("web", le.title()), List.of());
        }
        // No yt-dlp extractor AND not a readable page — the wire table's own
        // definition of UNSUPPORTED_URL.
        throw new ExtractionException(ErrorCode.UNSUPPORTED_URL,
                "Weavr can't read links from this site yet.");
    }

    private ExtractionResult extractPdf(String url) {
        Optional<String> text = pdfExtractor.extract(url);
        if (text.isPresent() && text.get().length() >= USABLE_TEXT_THRESHOLD) {
            return new ExtractionResult("pdf", text.get(), false, false,
                    ExtractionMetadata.empty("pdf", null), List.of());
        }
        throw new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE,
                "Weavr couldn't find any readable text in that PDF.");
    }

    private Optional<String> fetchCaptions(String url) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-extraction-subs-");
            return ytDlp.fetchCaptions(url, workDir);
        } catch (IOException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR, "Could not create a temp directory for subtitles.", e);
        } catch (YtDlpFailedException e) {
            // A caption fetch that fails is not fatal — metadata may still carry the result.
            log.debug("Caption fetch failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (ProcessExecutionException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR, "yt-dlp is not runnable: " + e.getMessage(), e);
        } finally {
            TempDirs.deleteQuietly(workDir);
        }
    }

    private Optional<ArtifactStore.StoredArtifact> downloadAudioArtifact(String url, int maxDurationSeconds, String prefix) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-extraction-audio-");
            Optional<Path> audio = ytDlp.downloadAudio(url, workDir, maxDurationSeconds);
            if (audio.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(artifacts.put(prefix, "audio", Files.readAllBytes(audio.get())));
        } catch (IOException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR, "Could not create a temp directory for audio.", e);
        } catch (YtDlpFailedException e) {
            ExtractionException mapped = YtDlpErrors.toException(e);
            if (mapped.code().retryable()) {
                throw mapped;
            }
            log.debug("Audio download failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (ProcessExecutionException e) {
            throw new ExtractionException(ErrorCode.INTERNAL_ERROR, "yt-dlp is not runnable: " + e.getMessage(), e);
        } finally {
            TempDirs.deleteQuietly(workDir);
        }
    }

    private static int resolvedDuration(ExtractionOptions options, int fallback) {
        return options.maxDurationSeconds() != null && options.maxDurationSeconds() > 0
                ? options.maxDurationSeconds() : fallback;
    }

    private static ExtractionResult textResult(String source, String text, String platform, SourceMetadata metadata) {
        return new ExtractionResult(source, text, false, false, ExtractionMetadata.from(platform, metadata), List.of());
    }

    /** Captions carry the speech; the title and uploader give the model context. */
    private static String combine(String captions, SourceMetadata metadata) {
        String header = metadata.asText();
        return header.isBlank() ? captions : header + "\n\n" + captions;
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
}
