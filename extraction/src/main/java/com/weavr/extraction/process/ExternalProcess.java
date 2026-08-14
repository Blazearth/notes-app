package com.weavr.extraction.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an external binary — yt-dlp, ffmpeg, tesseract — and returns what it
 * wrote. Ported verbatim from {@code api}'s {@code ExternalProcess}
 * (docs/extraction-architecture.md Phase 3: "port ExternalProcess's
 * discipline wholesale... it is the best-tested code in the pipeline and
 * should not be rewritten from memory").
 *
 * <p>Every rule here exists because breaking it produces a <em>hang</em> rather
 * than an error, which is the worst failure mode to diagnose after the fact:
 *
 * <ul>
 *   <li><b>Both pipes are drained concurrently.</b> A process that fills the
 *       stderr buffer while the parent is blocked reading stdout deadlocks
 *       forever. This is the single most common {@code ProcessBuilder} bug and it
 *       looks exactly like "yt-dlp is slow".</li>
 *   <li><b>Every run is bounded.</b> {@code waitFor(timeout)} then
 *       {@code destroyForcibly()}. A hung child otherwise pins a worker
 *       permanently, and with a small concurrency limit that is the whole
 *       pipeline.</li>
 *   <li><b>Output is capped.</b> An unbounded read of a misbehaving process is
 *       an OOM on a small instance. Reads stop at the cap and the process is
 *       killed.</li>
 *   <li><b>stdin is closed immediately.</b> A tool that decides to prompt would
 *       otherwise wait for input nobody is going to send.</li>
 * </ul>
 */
@Component
public class ExternalProcess {

    private static final Logger log = LoggerFactory.getLogger(ExternalProcess.class);

    /** Plenty for JSON metadata and subtitle text; small enough to be safe. */
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {

        public boolean succeeded() {
            return exitCode == 0 && !timedOut && !truncated;
        }
    }

    public Result run(List<String> command, Duration timeout) {
        return run(command, timeout, null, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public Result run(List<String> command, Duration timeout, Path workingDirectory) {
        return run(command, timeout, workingDirectory, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public Result run(List<String> command, Duration timeout, Path workingDirectory, int maxOutputBytes) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }

        Process process = null;
        StreamPump outPump = null;
        StreamPump errPump = null;
        try {
            process = builder.start();

            // Close stdin at once: a tool that decides to prompt would otherwise
            // block waiting for input that is never coming.
            process.getOutputStream().close();

            // Separate threads, started before any waiting happens. Draining one
            // stream at a time is the deadlock.
            outPump = StreamPump.start(process.getInputStream(), maxOutputBytes, "stdout");
            errPump = StreamPump.start(process.getErrorStream(), maxOutputBytes, "stderr");

            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.warn("Command timed out after {} and was killed: {}", timeout, command.getFirst());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(-1, outPump.join(), errPump.join(), true, false);
            }

            String stdout = outPump.join();
            String stderr = errPump.join();
            boolean truncated = outPump.truncated() || errPump.truncated();
            if (truncated) {
                log.warn("Command output exceeded {} bytes and was truncated: {}",
                        maxOutputBytes, command.getFirst());
            }
            return new Result(process.exitValue(), stdout, stderr, false, truncated);

        } catch (IOException e) {
            // Most often: the binary is not on PATH at all.
            throw new ProcessExecutionException(
                    "Could not run " + command.getFirst() + ": " + e.getMessage(), e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new ProcessExecutionException("Interrupted while running " + command.getFirst(), e);

        } finally {
            // A killed process's pumps see EOF and finish on their own; this is
            // belt-and-braces so no reader thread outlives the call.
            if (outPump != null) outPump.stop();
            if (errPump != null) errPump.stop();
        }
    }

    /** Reads one stream to completion on its own thread, with a hard cap. */
    private static final class StreamPump {

        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private StreamPump(InputStream stream, int maxBytes, String name) {
            this.thread = new Thread(() -> {
                byte[] chunk = new byte[8192];
                int total = 0;
                try (stream) {
                    int read;
                    while ((read = stream.read(chunk)) != -1) {
                        if (total >= maxBytes) {
                            // Keep reading and discarding: stopping here would
                            // refill the pipe buffer and block the child again.
                            truncated = true;
                            continue;
                        }
                        int allowed = Math.min(read, maxBytes - total);
                        buffer.write(chunk, 0, allowed);
                        total += allowed;
                        if (allowed < read) {
                            truncated = true;
                        }
                    }
                } catch (IOException e) {
                    // The stream closing under us is normal when the process is
                    // force-killed; whatever was captured is still returned.
                }
            }, "weavr-extraction-proc-" + name);
            this.thread.setDaemon(true);
        }

        static StreamPump start(InputStream stream, int maxBytes, String name) {
            StreamPump pump = new StreamPump(stream, maxBytes, name);
            pump.thread.start();
            return pump;
        }

        String join() {
            try {
                // The process has already exited or been killed by this point,
                // so EOF is imminent; the bound is only a safety net.
                thread.join(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }

        void stop() {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
    }
}
