package com.weavr.extraction.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.weavr.extraction.ocr.OcrProperties;
import com.weavr.extraction.process.ExternalProcess;
import com.weavr.extraction.process.ProcessExecutionException;
import com.weavr.extraction.ytdlp.YtDlpProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /health} — deliberately not auth-gated (see
 * {@link com.weavr.extraction.auth.SharedSecretFilter}), since a platform's
 * own health check has no bearer token to send. Reports which toolchain
 * binaries are actually reachable and the yt-dlp version actually running —
 * Phase 6's own gate ("a stale extractor is visible rather than inferred"),
 * done now rather than deferred since it costs nothing to check once at
 * startup.
 *
 * <p>Checked once, at startup, not per request: these binaries only change on
 * a container rebuild, and shelling out on every health poll would be pure
 * waste.
 */
@RestController
class HealthController {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);

    private final ExternalProcess processes;
    private final YtDlpProperties ytDlp;
    private final OcrProperties ocr;
    private final String ffmpegBinary;

    private volatile Map<String, Object> cached;

    HealthController(ExternalProcess processes, YtDlpProperties ytDlp, OcrProperties ocr,
                     com.weavr.extraction.ocr.FfmpegProperties ffmpeg) {
        this.processes = processes;
        this.ytDlp = ytDlp;
        this.ocr = ocr;
        this.ffmpegBinary = ffmpeg.binary();
    }

    @PostConstruct
    void checkToolchain() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("ytDlpVersion", version(ytDlp.binary(), "--version"));
        status.put("ffmpegAvailable", available(ffmpegBinary, "-version"));
        status.put("tesseractAvailable", ocr.enabled() ? available(ocr.binary(), "--version") : "disabled");
        this.cached = Map.copyOf(status);
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return cached;
    }

    private String version(String binary, String flag) {
        try {
            ExternalProcess.Result result = processes.run(List.of(binary, flag), CHECK_TIMEOUT);
            return result.succeeded() ? result.stdout().strip() : "unknown (exit " + result.exitCode() + ")";
        } catch (ProcessExecutionException e) {
            return "not runnable";
        }
    }

    private boolean available(String binary, String flag) {
        try {
            return processes.run(List.of(binary, flag), CHECK_TIMEOUT).succeeded();
        } catch (ProcessExecutionException e) {
            return false;
        }
    }
}
