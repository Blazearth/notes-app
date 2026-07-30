package com.weavr.api.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These run a real child process on purpose.
 *
 * <p>Every rule {@link ExternalProcess} enforces fails as a *hang*, not an
 * exception — so the only way to prove it is to reproduce the hang conditions
 * and assert they do not happen. A mocked {@code Process} would pass whether or
 * not the code were correct.
 */
class ExternalProcessTest {

    private final ExternalProcess processes = new ExternalProcess();

    private static List<String> helper(String mode) {
        String javaHome = System.getProperty("java.home");
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(javaHome, "bin", executable);
        assertThat(Files.isRegularFile(java)).as("java binary at %s", java).isTrue();

        return List.of(
                java.toString(),
                "-cp", System.getProperty("java.class.path"),
                ProcessTestHelper.class.getName(),
                mode);
    }

    /**
     * The one that matters. A child writing ~1 MB to stdout *and* stderr fills
     * both OS pipe buffers; draining them sequentially deadlocks forever.
     */
    @Test
    @Timeout(60)
    void drainsBothPipesConcurrently() {
        ExternalProcess.Result result = processes.run(helper("flood"), Duration.ofSeconds(45));

        assertThat(result.timedOut()).as("a deadlock would surface as a timeout").isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(ProcessTestHelper.FLOOD_BYTES);
        assertThat(result.stderr()).hasSize(ProcessTestHelper.FLOOD_BYTES);
    }

    @Test
    @Timeout(60)
    void killsAProcessThatNeverExits() {
        Instant started = Instant.now();
        ExternalProcess.Result result = processes.run(helper("hang"), Duration.ofSeconds(2));
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        // Bounded by the timeout, not by the child's willingness to stop.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    @Timeout(60)
    void surfacesNonZeroExitAndStderr() {
        ExternalProcess.Result result = processes.run(helper("fail"), Duration.ofSeconds(45));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.stderr()).contains("Unsupported URL");
    }

    @Test
    @Timeout(60)
    void capturesBothStreamsSeparately() {
        ExternalProcess.Result result = processes.run(helper("both"), Duration.ofSeconds(45));

        assertThat(result.stdout()).isEqualTo("out-content");
        assertThat(result.stderr()).isEqualTo("err-content");
        assertThat(result.succeeded()).isTrue();
    }

    /**
     * Truncation must not deadlock either: the pump keeps draining and
     * discarding past the cap, because ceasing to read would refill the pipe and
     * block the child.
     */
    @Test
    @Timeout(60)
    void truncatesOversizedOutputWithoutBlocking() {
        ExternalProcess.Result result =
                processes.run(helper("flood"), Duration.ofSeconds(45), null, 4096);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncated()).isTrue();
        assertThat(result.succeeded()).as("truncated output is not a success").isFalse();
        assertThat(result.stdout()).hasSize(4096);
    }

    @Test
    void reportsAMissingBinaryClearly() {
        assertThatThrownBy(() ->
                processes.run(List.of("weavr-definitely-not-a-real-binary"), Duration.ofSeconds(5)))
                .isInstanceOf(ProcessExecutionException.class)
                .hasMessageContaining("weavr-definitely-not-a-real-binary");
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> processes.run(List.of(), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
