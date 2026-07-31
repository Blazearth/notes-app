package com.weavr.api.pipeline.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.weavr.api.pipeline.ExternalProcess;
import com.weavr.api.pipeline.ProcessExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FfmpegClientTest {

    private final ExternalProcess processes = mock(ExternalProcess.class);
    private final FfmpegClient client =
            new FfmpegClient(processes, new FfmpegProperties("ffmpeg", Duration.ofSeconds(60)));

    private static ExternalProcess.Result ok() {
        return new ExternalProcess.Result(0, "", "", false, false);
    }

    private static ExternalProcess.Result failed() {
        return new ExternalProcess.Result(1, "", "ffmpeg: invalid data found", false, false);
    }

    @Test
    void succeedsWhenFfmpegExitsCleanAndWritesTheOutputFile(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("in.m4a");
        Path output = dir.resolve("out.wav");
        Files.writeString(input, "not real audio, just needs to exist");
        // The real ffmpeg would write this; the mock stands in for that.
        Files.writeString(output, "fake wav bytes");
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        client.downmixToWav(input, output);

        assertThat(output).exists();
    }

    @Test
    void throwsWhenFfmpegExitsNonZero(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("in.m4a");
        Path output = dir.resolve("out.wav");
        Files.writeString(input, "not real audio");
        when(processes.run(anyList(), any(Duration.class))).thenReturn(failed());

        assertThatThrownBy(() -> client.downmixToWav(input, output))
                .isInstanceOf(ProcessExecutionException.class)
                .hasMessageContaining("in.m4a");
    }

    @Test
    void throwsWhenFfmpegClaimsSuccessButWroteNoOutput(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("in.m4a");
        Path output = dir.resolve("out.wav");
        Files.writeString(input, "not real audio");
        // No file written at `output`, unlike the real binary.
        when(processes.run(anyList(), any(Duration.class))).thenReturn(ok());

        assertThatThrownBy(() -> client.downmixToWav(input, output))
                .isInstanceOf(ProcessExecutionException.class);
    }
}
