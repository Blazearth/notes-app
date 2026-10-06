package com.weavr.api.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.audio.AsrTranscriber;
import com.weavr.api.pipeline.health.ExtractionAttempt;
import com.weavr.api.pipeline.health.ExtractionAttemptRecorder;
import com.weavr.api.pipeline.health.ExtractionFailureCategory;
import com.weavr.api.pipeline.health.ExtractionFailureClassifier;
import com.weavr.api.pipeline.health.SourcePlatform;
import com.weavr.api.pipeline.ocr.VisualTextExtractor;
import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
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
 *   <li><b>The visual tier</b> — keyframes and local OCR, last because it is
 *       the only step that downloads video. This is the overlay-text-only case:
 *       a recipe Reel whose ingredients exist purely as burned-in pixels
 *       defeats every step above, and is common.</li>
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
public class ExtractionCascade implements SourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCascade.class);

    /** Below this, metadata alone is not worth calling an extraction. */
    private static final int USABLE_TEXT_THRESHOLD = 40;

    /**
     * Below this, a title/uploader/hashtag blob is treated the same as no
     * description at all, and the cascade keeps going rather than stopping.
     *
     * <p>{@code USABLE_TEXT_THRESHOLD} alone let a save through on title and
     * uploader-name length, which pad the count without carrying a single
     * fact — measured against a real Instagram Reel ("FULL BODY, no equipment
     * needed, hashtags only") that classified as a
     * workout at 0.9+ confidence three separate times with {@code exercises: []}
     * every time, because the actual routine only exists as a visual
     * demonstration and the cascade never got past "some text was present" to
     * try ASR or the visual/OCR tier built for exactly this case. Checked
     * against the description (and pinned comment, when the yt-dlp path
     * fetched one) alone, with hashtags and @mentions stripped first, so a
     * caption that is only tags never counts as an explanation.
     */
    private static final int DESCRIPTION_SUBSTANTIVE_THRESHOLD = 50;

    private static final Pattern SOCIAL_NOISE = Pattern.compile("[#@][\\w.]+");

    /**
     * How long {@link #withPinnedComment} waits for the supplemental yt-dlp
     * fetch before giving up and forwarding the pipeline without it. The
     * fetch itself is bounded by {@code probeTimeout} (60s default) and is
     * expected to fail slowly on Render (a bot-check response, not an
     * instant one) — this is a separate, much shorter budget so a save that
     * already has everything it needs from RapidAPI is never held up
     * waiting on a best-effort extra that usually won't arrive there.
     */
    private static final Duration DEFAULT_PINNED_COMMENT_WAIT = Duration.ofSeconds(3);

    /** Provider names in attempt records and the {@code provider} stage-payload key. */
    static final String PROVIDER_RAPIDAPI = "rapidapi";
    static final String PROVIDER_YOUTUBE_DATA_API = "youtube_data_api";
    static final String PROVIDER_YTDLP = "ytdlp";
    static final String PROVIDER_YTDLP_CAPTIONS = "ytdlp_captions";

    private final YtDlpClient ytDlp;
    private final RapidYtClient rapidYt;
    private final YouTubeDataApiClient youtubeData;
    private final AsrTranscriber asr;
    private final VisualTextExtractor visual;
    private final LinkExtractor linkExtractor;
    private final PdfExtractor pdfExtractor;
    private final ExtractionAttemptRecorder attempts;

    /** Test-only seam: real callers always get {@link #DEFAULT_PINNED_COMMENT_WAIT}. */
    private Duration pinnedCommentWait = DEFAULT_PINNED_COMMENT_WAIT;

    // Named, daemon threads: exists only so the supplemental pinned-comment
    // fetch can keep running past its wait window without blocking the
    // cascade, and so giving up on it (Future#cancel(true)) actually
    // interrupts the worker thread — which ExternalProcess.run treats
    // exactly like its own timeout, destroying the yt-dlp process rather
    // than leaving it running unattended in the background.
    private final ExecutorService pinnedCommentExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "weavr-pinned-comment");
        thread.setDaemon(true);
        return thread;
    });

    ExtractionCascade(YtDlpClient ytDlp, RapidYtClient rapidYt, YouTubeDataApiClient youtubeData,
                      AsrTranscriber asr, VisualTextExtractor visual, LinkExtractor linkExtractor,
                      PdfExtractor pdfExtractor, ExtractionAttemptRecorder attempts) {
        this.ytDlp = ytDlp;
        this.rapidYt = rapidYt;
        this.youtubeData = youtubeData;
        this.asr = asr;
        this.visual = visual;
        this.linkExtractor = linkExtractor;
        this.pdfExtractor = pdfExtractor;
        this.attempts = attempts;
    }

    /** Test-only: overrides how long {@link #withPinnedComment} waits, so a deliberately slow mock doesn't cost the suite the real 3s. */
    void setPinnedCommentWaitForTesting(Duration wait) {
        this.pinnedCommentWait = wait;
    }

    /**
     * @param text     the assembled blob, ready for the model
     * @param source   which cascade step produced it
     * @param metadata the probe result, kept for enrichment and the thumbnail
     * @param detail   extra breadcrumbs from the step that won, or empty. Only
     *                 the visual tier fills this in — its escalation rate is the
     *                 number Phase 4 has to watch, and it is invisible unless
     *                 the winning step records how it did
     */
    public record Extraction(String text, String source, SourceMetadata metadata,
                             Map<String, Object> detail) {

        public Extraction(String text, String source, SourceMetadata metadata) {
            this(text, source, metadata, Map.of());
        }
    }

    /**
     * @param saveId needed only so a vision escalation inside the visual tier
     *               can attribute its request in {@code gemini_calls}
     */
    @Override
    public Extraction extractFromUrl(String url, UUID saveId) {
        if (looksLikePdf(url)) {
            return extractPdf(url);
        }

        // --- RapidAPI fast path for YouTube (no bot check, includes transcript) ---
        String videoId = RapidYtClient.extractVideoId(url);
        if (videoId != null) {
            long rapidStart = System.nanoTime();
            RapidYtClient.ProbeOutcome rapidOutcome = rapidYt.probeDetailed(url);
            if (rapidOutcome.attempted()) {
                record(url, PROVIDER_RAPIDAPI, rapidOutcome.category(), rapidOutcome.httpStatus(),
                        rapidStart, null, null);
            }
            Optional<RapidYtClient.ProbeResult> rapid = rapidOutcome.result();
            if (rapid.isPresent()) {
                RapidYtClient.ProbeResult r = rapid.get();
                log.info("RapidAPI probe succeeded for {}", url);
                // RapidAPI's own response never carries a pinned comment (see
                // YtDlpClient.fetchPinnedComment's javadoc) — this is the only
                // way a YouTube save gets one. Best-effort: it never throws,
                // so a failure here (the common case on Render) leaves
                // everything below exactly as it was without it.
                SourceMetadata metadata = withPinnedComment(r.metadata(), url);
                // Transcript available — best case, no further calls needed
                if (r.transcript().isPresent()
                        && r.transcript().get().length() >= USABLE_TEXT_THRESHOLD) {
                    return new Extraction(combine(r.transcript().get(), metadata),
                            "captions", metadata, providerDetail(PROVIDER_RAPIDAPI));
                }
                // No transcript but metadata may be enough
                String metaText = metadata.asText();
                if (metaText.length() >= USABLE_TEXT_THRESHOLD
                        && hasSubstantiveDescription(metadata)) {
                    return new Extraction(metaText, "metadata", metadata, providerDetail(PROVIDER_RAPIDAPI));
                }
                // Metadata and every caption track RapidAPI offered are both too
                // thin (F5, docs/extraction-architecture.md). Falling through to
                // continueFromMetadata here would mean ASR/visual downloading
                // audio or video straight from YouTube via yt-dlp — exactly the
                // datacenter-IP-blocked path RapidAPI exists to avoid, confirmed
                // dead on this deployment (CLAUDE.md: 8/8 real YouTube URLs hit
                // the identical bot-check error from Render's IP, with or without
                // cookies or a player-client override). Even setting the block
                // aside, spending a full video download on a platform whose fast
                // metadata path already came up empty contradicts the reason that
                // path exists. Fail fast and honestly instead of a silent,
                // multi-minute attempt at a path that cannot succeed.
                log.info("RapidAPI metadata and every caption track were too thin for {}", url);
                throw new PermanentJobException("youtube_no_usable_text",
                        "Weavr couldn't find enough text in that YouTube video to work with.");
            }
            log.warn("RapidAPI probe returned empty for {} ({}), trying the YouTube Data API",
                    url, rapidOutcome.category());

            Optional<Extraction> fromDataApi = extractFromYouTubeDataApi(url, videoId);
            if (fromDataApi.isPresent()) {
                return fromDataApi.get();
            }
        }

        // --- yt-dlp path (non-YouTube, or both YouTube API providers unavailable/failed/thin) ---
        SourceMetadata metadata;
        try {
            metadata = probe(url);
        } catch (PermanentJobException e) {
            if ("unsupported_source".equals(e.errorCode())) {
                return extractLink(url);
            }
            throw e;
        }

        return withProvider(continueFromMetadata(url, saveId, metadata), PROVIDER_YTDLP);
    }

    /**
     * The legitimate second YouTube provider, reached only after RapidAPI came
     * back empty. Deliberately placed <em>before</em> yt-dlp rather than after:
     * on Render yt-dlp's YouTube probe is confirmed bot-blocked (8/8), so
     * putting it first would spend up to a 60s probe on a path that cannot
     * succeed there before reaching one that can.
     *
     * <p>Metadata only — it never claims captions. When the description is too
     * thin to stand alone this returns empty and the cascade falls through to
     * yt-dlp exactly as it did before this provider existed, so a residential
     * deploy (or a lifted block) still gets yt-dlp's captions.
     *
     * <p>The one case that ends the save here: Google answers that no such
     * public video exists. That's authoritative, and falling through would
     * only turn "deleted video" into a bot-check error that the queue then
     * retries for hours.
     */
    private Optional<Extraction> extractFromYouTubeDataApi(String url, String videoId) {
        long start = System.nanoTime();
        YouTubeDataApiClient.Outcome outcome = youtubeData.fetch(videoId);
        if (!outcome.attempted()) {
            return Optional.empty();
        }
        record(url, PROVIDER_YOUTUBE_DATA_API, outcome.category(), outcome.httpStatus(), start, null,
                outcome.detail());

        if (outcome.category() == ExtractionFailureCategory.CONTENT_UNAVAILABLE) {
            throw new PermanentJobException("content_unavailable", "That post isn't available any more.");
        }
        if (outcome.metadata().isEmpty()) {
            return Optional.empty();
        }

        SourceMetadata metadata = withPinnedComment(outcome.metadata().get(), url);
        String text = metadata.asText();
        if (text.length() >= USABLE_TEXT_THRESHOLD && hasSubstantiveDescription(metadata)) {
            return Optional.of(new Extraction(text, "metadata", metadata,
                    providerDetail(PROVIDER_YOUTUBE_DATA_API)));
        }
        log.info("YouTube Data API metadata too thin for {}, falling through to yt-dlp", url);
        return Optional.empty();
    }

    /** Runs captions → metadata text → ASR → visual against an already-probed metadata. */
    private Extraction continueFromMetadata(String url, UUID saveId, SourceMetadata metadata) {
        if (metadata.hasCaptions()) {
            Optional<String> captions = fetchCaptions(url);
            if (captions.isPresent() && captions.get().length() >= USABLE_TEXT_THRESHOLD) {
                return new Extraction(combine(captions.get(), metadata), "captions", metadata);
            }
            log.debug("Captions were advertised for {} but produced nothing usable", url);
        }

        String metadataText = metadata.asText();
        if (metadataText.length() >= USABLE_TEXT_THRESHOLD && hasSubstantiveDescription(metadata)) {
            return new Extraction(metadataText, "metadata", metadata);
        }

        Optional<String> transcript = asr.transcribe(url);
        if (transcript.isPresent() && transcript.get().length() >= USABLE_TEXT_THRESHOLD) {
            return new Extraction(combine(transcript.get(), metadata), "asr", metadata);
        }

        Optional<VisualTextExtractor.VisualText> visualText = visual.extract(url, saveId);
        if (visualText.isPresent() && !visualText.get().text().isBlank()) {
            VisualTextExtractor.VisualText v = visualText.get();
            return new Extraction(combine(v.text(), metadata), v.source(), metadata,
                    VisualTextExtractor.stagePayload(v));
        }

        throw new PermanentJobException("no_text_extracted",
                "Weavr couldn't find any text in that post to work with.");
    }

    private SourceMetadata probe(String url) {
        long start = System.nanoTime();
        try {
            SourceMetadata metadata = ytDlp.probe(url);
            record(url, PROVIDER_YTDLP, ExtractionFailureCategory.SUCCESS, null, start, ytDlp.version(), null);
            return metadata;
        } catch (YtDlpFailedException e) {
            record(url, PROVIDER_YTDLP, ExtractionFailureClassifier.fromYtDlp(e), null, start, ytDlp.version(),
                    YtDlpErrors.classify(e.stderr()).errorCode());
            throw YtDlpErrors.toException(e);
        } catch (ProcessExecutionException e) {
            record(url, PROVIDER_YTDLP, ExtractionFailureCategory.UNKNOWN, null, start, null, "binary_not_runnable");
            // yt-dlp missing from PATH is a deployment fault, not the save's.
            // Retryable so the work survives a container that is rebuilt with it.
            throw new RetryableJobException("yt-dlp is not runnable: " + e.getMessage(), e);
        }
    }

    private Optional<String> fetchCaptions(String url) {
        Path workDir = null;
        long start = System.nanoTime();
        try {
            workDir = Files.createTempDirectory("weavr-subs-");
            Optional<String> captions = ytDlp.fetchCaptions(url, workDir);
            record(url, PROVIDER_YTDLP_CAPTIONS, ExtractionFailureCategory.SUCCESS, null, start, ytDlp.version(),
                    captions.isPresent() ? null : "no_captions_written");
            return captions;
        } catch (IOException e) {
            throw new RetryableJobException("Could not create a temp directory for subtitles", e);
        } catch (YtDlpFailedException e) {
            record(url, PROVIDER_YTDLP_CAPTIONS, ExtractionFailureClassifier.fromYtDlp(e), null, start,
                    ytDlp.version(), YtDlpErrors.classify(e.stderr()).errorCode());
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

    /** One attempt record per provider call. Carries the platform, never the URL. */
    private void record(String url, String provider, ExtractionFailureCategory category, Integer httpStatus,
                        long startNanos, String ytDlpVersion, String detail) {
        attempts.record(new ExtractionAttempt("save", SourcePlatform.detect(url), provider, category,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), httpStatus, ytDlpVersion, detail,
                Instant.now()));
    }

    private static Map<String, Object> providerDetail(String provider) {
        return Map.of("provider", provider);
    }

    /**
     * Tags which provider an extraction came from, so {@code save_stages}
     * can answer "how often does the fallback actually carry a save".
     * Merged, never nested: the visual tier's detail is already there.
     */
    private static Extraction withProvider(Extraction extraction, String provider) {
        Map<String, Object> detail = new LinkedHashMap<>(extraction.detail());
        detail.putIfAbsent("provider", provider);
        return new Extraction(extraction.text(), extraction.source(), extraction.metadata(), detail);
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
        return new SourceMetadata(null, title, null, null, null, null, List.of(), List.of(), null);
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

    /**
     * A title and an uploader name are boilerplate the model already gets for
     * free from every save; they must not count toward "is this text worth
     * stopping on." Only the description and a fetched pinned comment can,
     * and hashtags/@mentions inside them are stripped first so a caption that
     * is purely tags scores as empty rather than as a paragraph.
     */
    private static boolean hasSubstantiveDescription(SourceMetadata metadata) {
        String description = metadata.description() == null ? "" : metadata.description();
        String stripped = SOCIAL_NOISE.matcher(description).replaceAll("").strip();
        if (stripped.length() >= DESCRIPTION_SUBSTANTIVE_THRESHOLD) {
            return true;
        }
        String comment = metadata.pinnedComment();
        return comment != null && comment.strip().length() >= DESCRIPTION_SUBSTANTIVE_THRESHOLD;
    }

    /**
     * Swaps in a fetched pinned comment when {@link YtDlpClient#fetchPinnedComment}
     * found one within {@link #pinnedCommentWait}; returns {@code metadata}
     * unchanged in every other case — an empty result, a swallowed yt-dlp
     * failure (see that method's javadoc), and a wait that ran out all look
     * identical from here, by design. {@link SourceMetadata#asText()} already
     * appends the pinned comment when present, so every caller below benefits
     * without a second threshold check.
     */
    private SourceMetadata withPinnedComment(SourceMetadata metadata, String url) {
        Future<Optional<String>> future = pinnedCommentExecutor.submit(() -> ytDlp.fetchPinnedComment(url));
        Optional<String> comment;
        try {
            comment = future.get(pinnedCommentWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // cancel(true) interrupts the worker thread. That worker is
            // blocked inside ExternalProcess.run's process.waitFor(...),
            // which treats an InterruptedException exactly like its own
            // timeout: destroyForcibly() then return, so the yt-dlp process
            // doesn't keep running unattended after the cascade moves on.
            future.cancel(true);
            log.debug("Supplemental pinned-comment fetch for {} exceeded {}, forwarding without it",
                    url, pinnedCommentWait);
            return metadata;
        } catch (Exception e) {
            // fetchPinnedComment itself never throws, but this path must
            // never cost the save regardless — defend anyway.
            log.debug("Supplemental pinned-comment fetch for {} failed unexpectedly: {}", url, e.getMessage());
            return metadata;
        }
        return comment.filter(c -> !c.isBlank())
                .map(c -> new SourceMetadata(metadata.id(), metadata.title(), metadata.description(),
                        metadata.uploader(), metadata.durationSeconds(), metadata.thumbnailUrl(),
                        metadata.captionLanguages(), metadata.autoCaptionLanguages(), c))
                .orElse(metadata);
    }

    /** Captions carry the speech; the title and uploader give the model context. */
    private static String combine(String captions, SourceMetadata metadata) {
        String header = metadata.asText();
        return header.isBlank() ? captions : header + "\n\n" + captions;
    }

    /** Stage payload for {@code save_stages}, kept small — it is a breadcrumb. */
    public static Map<String, Object> stagePayload(Extraction extraction) {
        SourceMetadata metadata = extraction.metadata();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", extraction.source());
        payload.put("textLength", extraction.text().length());
        payload.put("title", metadata.title() == null ? "" : metadata.title());
        payload.put("uploader", metadata.uploader() == null ? "" : metadata.uploader());
        payload.put("hasCaptions", metadata.hasCaptions());
        // "source" is the step name in both maps and carries the same value, so
        // the visual tier's detail is merged rather than nested.
        payload.putAll(extraction.detail());
        return payload;
    }
}
