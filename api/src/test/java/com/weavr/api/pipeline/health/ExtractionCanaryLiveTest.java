package com.weavr.api.pipeline.health;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real canary against the real sites. <b>Opt-in</b>, like
 * {@code YtDlpLiveTest}: a third party blocking us must not turn the build red.
 *
 * <p>This is a measurement, not a pass/fail gate — it asserts only that every
 * target was checked and recorded, then prints each result. Read the table.
 * Note where it runs: from a dev machine this is a residential IP, which is
 * precisely NOT what Render's datacenter IP sees.
 *
 * <pre>
 *   WEAVR_LIVE_CANARY=1 ./mvnw test -Dtest=ExtractionCanaryLiveTest
 *   # optional, each enables one more provider:
 *   WEAVR_RAPID_YT_API_KEY=... WEAVR_YOUTUBE_API_KEY=... WEAVR_LIVE_CANARY=1 ./mvnw test -Dtest=ExtractionCanaryLiveTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WEAVR_LIVE_CANARY", matches = "1")
class ExtractionCanaryLiveTest {

    @Test
    void probesEveryDefaultTargetThroughEveryConfiguredProvider() {
        ExtractionAttemptRecorder recorder = new ExtractionAttemptRecorder();
        ExtractionCanary canary = new ExtractionCanary(
                LiveClients.ytDlp(), LiveClients.rapidYt(), LiveClients.youtubeData(), recorder);

        List<ExtractionAttempt> results = canary.run();

        System.out.println("CANARY platform | provider | category | latency_ms | http | ytdlp | detail");
        results.forEach(a -> System.out.printf("CANARY %s | %s | %s | %d | %s | %s | %s%n",
                a.platform(), a.provider(), a.category(), a.latencyMs(), a.httpStatus(), a.ytDlpVersion(),
                a.detail()));

        assertThat(results.stream().filter(a -> a.provider().equals("ytdlp")))
                .hasSize(ExtractionCanary.DEFAULT_TARGETS.size());
        assertThat(recorder.recent()).isEqualTo(results);
    }
}
