package com.weavr.api.pipeline.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.weavr.api.pipeline.ExternalProcess;
import com.weavr.api.pipeline.ProcessExecutionException;
import org.springframework.stereotype.Component;

/**
 * Downmixes whatever yt-dlp downloaded to 16 kHz mono — what Whisper expects,
 * and a fraction of the size of the source audio.
 */
@Component
public class FfmpegClient {

    private final ExternalProcess processes;
    private final FfmpegProperties properties;

    FfmpegClient(ExternalProcess processes, FfmpegProperties properties) {
        this.processes = processes;
        this.properties = properties;
    }

    /**
     * Writes {@code output} directly rather than returning bytes: ffmpeg's own
     * stdout/stderr are diagnostic text, not the audio, so there is nothing to
     * capture from {@link ExternalProcess} beyond the exit code.
     */
    public void downmixToWav(Path input, Path output) {
        ExternalProcess.Result result = processes.run(List.of(
                properties.binary(),
                "-y",
                "-i", input.toString(),
                "-ac", "1",
                "-ar", "16000",
                "-vn",
                output.toString()), properties.timeout());

        if (!result.succeeded() || !Files.exists(output)) {
            throw new ProcessExecutionException(
                    "ffmpeg could not downmix " + input.getFileName() + ": " + describe(result), null);
        }
    }

    private static String describe(ExternalProcess.Result result) {
        if (result.timedOut()) {
            return "timed out";
        }
        return "exited " + result.exitCode() + ": " + result.stderr();
    }
}
