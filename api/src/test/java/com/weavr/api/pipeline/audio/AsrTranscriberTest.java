package com.weavr.api.pipeline.audio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.weavr.api.asr.GroqClient;
import com.weavr.api.asr.GroqProperties;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration only — download, downmix, transcribe, clean up — with each
 * collaborator mocked. {@link com.weavr.api.asr.GroqClientTest} and
 * {@link FfmpegClientTest} cover their own mechanics.
 */
class AsrTranscriberTest {

    private final YtDlpClient ytDlp = mock(YtDlpClient.class);
    private final FfmpegClient ffmpeg = mock(FfmpegClient.class);
    private final GroqClient groq = mock(GroqClient.class);
    private final GroqProperties groqProperties =
            new GroqProperties("test-key", "whisper-large-v3-turbo", Duration.ofSeconds(30), 90);
    private final AsrTranscriber transcriber = new AsrTranscriber(ytDlp, ffmpeg, groq, groqProperties);

    /** Stands in for the real ffmpeg run, which FfmpegClientTest already covers. */
    private void stubDownmixWritesAFile() {
        doAnswer(inv -> {
            Path output = inv.getArgument(1);
            Files.write(output, "RIFF-fake-wav-bytes".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(ffmpeg).downmixToWav(any(Path.class), any(Path.class));
    }

    @Test
    void downloadsDownmixesAndTranscribes() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), eq(90)))
                .thenReturn(Optional.of(Path.of("audio.m4a")));
        stubDownmixWritesAFile();
        when(groq.transcribe(any(byte[].class), anyString()))
                .thenReturn("first brown the onions then add the stock and simmer");

        Optional<String> result = transcriber.transcribe("https://example.com/v");

        assertThat(result).contains("first brown the onions then add the stock and simmer");
    }

    @Test
    void returnsEmptyWhenTheSourceHasNoAudioTrack() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt())).thenReturn(Optional.empty());

        Optional<String> result = transcriber.transcribe("https://example.com/v");

        assertThat(result).isEmpty();
        verify(ffmpeg, never()).downmixToWav(any(), any());
        verify(groq, never()).transcribe(any(), anyString());
    }

    @Test
    void returnsEmptyWhenGroqTranscribesToBlank() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt()))
                .thenReturn(Optional.of(Path.of("audio.m4a")));
        stubDownmixWritesAFile();
        when(groq.transcribe(any(byte[].class), anyString())).thenReturn("   ");

        assertThat(transcriber.transcribe("https://example.com/v")).isEmpty();
    }

    /** A retryable download failure must not be swallowed into a false permanent failure. */
    @Test
    void propagatesARetryableDownloadFailure() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> transcriber.transcribe("https://example.com/v"))
                .isInstanceOf(RetryableJobException.class);
    }

    /**
     * A permanently-classified download failure (e.g. the video vanished
     * between the probe and this call) is not fatal to the whole extraction —
     * the cascade's own final failure is the honest outcome, not this one.
     */
    @Test
    void swallowsAPermanentDownloadFailureIntoEmpty() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt()))
                .thenThrow(new YtDlpFailedException("yt-dlp exited 1",
                        "ERROR: This video has been removed by the uploader"));

        assertThat(transcriber.transcribe("https://example.com/v")).isEmpty();
    }

    @Test
    void treatsAMissingBinaryAsRetryable() {
        when(ytDlp.downloadAudio(anyString(), any(Path.class), anyInt()))
                .thenThrow(new ProcessExecutionException("Could not run yt-dlp", new RuntimeException()));

        assertThatThrownBy(() -> transcriber.transcribe("https://example.com/v"))
                .isInstanceOf(RetryableJobException.class);
    }
}
