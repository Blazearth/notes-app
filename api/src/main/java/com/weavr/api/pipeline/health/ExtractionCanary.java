package com.weavr.api.pipeline.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.youtube.YouTubeVideoIds;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
import com.weavr.api.pipeline.ytdlp.SourceMetadata;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpErrors;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Probes a handful of long-lived public URLs through each provider, so a
 * stale extractor or a new IP block shows up in the logs before a user's
 * silent capture quietly fails.
 *
 * <p><b>Metadata probes only.</b> No media download, no captions file, no
 * Gemini call, no cookies-dependent target. One run is at most one request per
 * provider per target — detection, not load. yt-dlp is still probed for
 * YouTube even though it's known bot-blocked on Render: that's the only way
 * to notice if the block ever lifts, or starts hitting a second platform.
 *
 * <p>Scheduled the same way {@code RetentionSweeper} is (fixed delay from
 * start, because the free instance restarts too often for a cron to be
 * reliable), off unless {@code weavr.canary.enabled=true}. Runs on the shared
 * scheduler thread, never a job-worker slot — a canary can't take capacity
 * from a real save, only share the CPU with it for a few seconds.
 */
@Component
@ConditionalOnProperty(prefix = "weavr.canary", name = "enabled", havingValue = "true")
public class ExtractionCanary {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCanary.class);

    /**
     * Chosen for longevity, not content: each is either historically
     * significant or one of yt-dlp's own extractor test URLs, which its
     * maintainers keep resolving. Verified reachable from a residential IP on
     * 2026-10-01 (see the live test); swap one out if it ever 404s.
     */
    public static final List<String> DEFAULT_TARGETS = List.of(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "https://www.instagram.com/p/aye83DjauH/",
            "https://www.tiktok.com/@scout2015/video/6718335390845095173");

    private static final String EMPTY_METADATA = "title_chars=0,description_chars=0";

    private final List<String> targets;
    private final YtDlpClient ytDlp;
    private final RapidYtClient rapidYt;
    private final YouTubeDataApiClient youtubeData;
    private final ExtractionAttemptRecorder attempts;

    @Autowired
    public ExtractionCanary(YtDlpClient ytDlp, RapidYtClient rapidYt, YouTubeDataApiClient youtubeData,
                            ExtractionAttemptRecorder attempts) {
        this(DEFAULT_TARGETS, ytDlp, rapidYt, youtubeData, attempts);
    }

    public ExtractionCanary(List<String> targets, YtDlpClient ytDlp, RapidYtClient rapidYt,
                            YouTubeDataApiClient youtubeData, ExtractionAttemptRecorder attempts) {
        this.targets = List.copyOf(targets);
        this.ytDlp = ytDlp;
        this.rapidYt = rapidYt;
        this.youtubeData = youtubeData;
        this.attempts = attempts;
    }

    @Scheduled(
            initialDelayString = "${weavr.canary.initial-delay:PT10M}",
            fixedDelayString = "${weavr.canary.interval:PT12H}")
    public void scheduledRun() {
        try {
            List<ExtractionAttempt> results = run();
            long failed = results.stream().filter(a -> !a.success()).count();
            if (failed > 0) {
                log.warn("Extraction canary: {} of {} provider checks failed", failed, results.size());
            } else {
                log.info("Extraction canary: all {} provider checks passed", results.size());
            }
        } catch (RuntimeException e) {
            // Observation only. Must never take down the instance serving saves.
            log.warn("Extraction canary run failed, will retry next interval", e);
        }
    }

    /** One pass over every target. Every result is also recorded (and so logged). */
    public List<ExtractionAttempt> run() {
        List<ExtractionAttempt> results = new ArrayList<>();
        for (String url : targets) {
            String platform = SourcePlatform.detect(url);
            String videoId = YouTubeVideoIds.parse(url);
            if (videoId != null) {
                if (rapidYt.enabled()) {
                    results.add(timed(platform, "rapidapi", () -> {
                        RapidYtClient.ProbeOutcome outcome = rapidYt.probeDetailed(url);
                        return outcome.result().isPresent()
                                ? new Check(ExtractionFailureCategory.SUCCESS, outcome.httpStatus(),
                                        metadataDetail(outcome.result().get().metadata()))
                                : Check.failed(outcome.category(), outcome.httpStatus(), null);
                    }));
                }
                if (youtubeData.enabled()) {
                    results.add(timed(platform, "youtube_data_api", () -> {
                        YouTubeDataApiClient.Outcome outcome = youtubeData.fetch(videoId);
                        return outcome.metadata().isPresent()
                                ? new Check(ExtractionFailureCategory.SUCCESS, outcome.httpStatus(),
                                        metadataDetail(outcome.metadata().get()))
                                : Check.failed(outcome.category(), outcome.httpStatus(), outcome.detail());
                    }));
                }
            }
            results.add(timed(platform, "ytdlp", () -> probeYtDlp(url)));
        }
        return results;
    }

    private Check probeYtDlp(String url) {
        try {
            return Check.ok(metadataDetail(ytDlp.probe(url)));
        } catch (YtDlpFailedException e) {
            return Check.failed(ExtractionFailureClassifier.fromYtDlp(e), null,
                    YtDlpErrors.classify(e.stderr()).errorCode());
        } catch (ProcessExecutionException e) {
            return Check.failed(ExtractionFailureCategory.UNKNOWN, null, "binary_not_runnable");
        }
    }

    /**
     * A probe that "succeeds" with no title and no description is a silent
     * extractor breakage, not a pass — record it as one.
     */
    private static String metadataDetail(SourceMetadata metadata) {
        int title = metadata.title() == null ? 0 : metadata.title().length();
        int description = metadata.description() == null ? 0 : metadata.description().length();
        return "title_chars=" + title + ",description_chars=" + description;
    }

    private ExtractionAttempt timed(String platform, String provider, Supplier<Check> check) {
        long start = System.nanoTime();
        Check result;
        try {
            result = check.get();
        } catch (RuntimeException e) {
            result = Check.failed(ExtractionFailureClassifier.fromException(e), null, e.getClass().getSimpleName());
        }
        if (result.category() == ExtractionFailureCategory.SUCCESS && EMPTY_METADATA.equals(result.detail())) {
            result = Check.failed(ExtractionFailureCategory.EXTRACTOR_ERROR, null, "empty_metadata");
        }
        ExtractionAttempt attempt = new ExtractionAttempt("canary", platform, provider, result.category(),
                Duration.ofNanos(System.nanoTime() - start).toMillis(), result.httpStatus(),
                provider.equals("ytdlp") ? ytDlp.version() : null, result.detail(), Instant.now());
        attempts.record(attempt);
        return attempt;
    }

    private record Check(ExtractionFailureCategory category, Integer httpStatus, String detail) {
        static Check ok(String detail) {
            return new Check(ExtractionFailureCategory.SUCCESS, null, detail);
        }

        static Check failed(ExtractionFailureCategory category, Integer httpStatus, String detail) {
            return new Check(category, httpStatus, detail);
        }
    }
}
