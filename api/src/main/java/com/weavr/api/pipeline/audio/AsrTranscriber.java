package com.weavr.api.pipeline.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.weavr.api.asr.GroqClient;
import com.weavr.api.asr.GroqProperties;
import com.weavr.api.job.RetryableJobException;
import com.weavr.api.pipeline.ProcessExecutionException;
import com.weavr.api.pipeline.TempDirs;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpErrors;
import com.weavr.api.pipeline.ytdlp.YtDlpFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Step 3 of the extraction cascade — the last resort, reached only when
 * captions and metadata both come up empty. Downloads audio only (never
 * video), downmixes it, and hands it to Groq: three single-purpose
 * collaborators kept separate so each is testable without the other two.
 */
@Component
public class AsrTranscriber {

    private static final Logger log = LoggerFactory.getLogger(AsrTranscriber.class);

    private final YtDlpClient ytDlp;
    private final FfmpegClient ffmpeg;
    private final GroqClient groq;
    private final GroqProperties groqProperties;

    AsrTranscriber(YtDlpClient ytDlp, FfmpegClient ffmpeg, GroqClient groq, GroqProperties groqProperties) {
        this.ytDlp = ytDlp;
        this.ffmpeg = ffmpeg;
        this.groq = groq;
        this.groqProperties = groqProperties;
    }

    /**
     * @return the transcript, or empty if this source has no audio track to
     *         transcribe (not an error — the cascade's final failure covers it)
     */
    public Optional<String> transcribe(String url) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("weavr-asr-");

            Optional<Path> audio = ytDlp.downloadAudio(url, workDir, groqProperties.maxAudioSeconds());
            if (audio.isEmpty()) {
                return Optional.empty();
            }

            Path wav = workDir.resolve("audio.wav");
            ffmpeg.downmixToWav(audio.get(), wav);

            String text = groq.transcribe(Files.readAllBytes(wav), "audio.wav");
            return text.isBlank() ? Optional.empty() : Optional.of(text.strip());

        } catch (IOException e) {
            throw new RetryableJobException("Could not prepare audio for transcription", e);
        } catch (YtDlpFailedException e) {
            // Not fatal to the whole extraction — the cascade's final
            // no_text_extracted failure is the honest outcome, not this.
            log.debug("Audio download failed for {}: {}", url, e.getMessage());
            RuntimeException translated = YtDlpErrors.toException(e);
            if (translated instanceof RetryableJobException) {
                throw translated;
            }
            return Optional.empty();
        } catch (ProcessExecutionException e) {
            throw new RetryableJobException("yt-dlp or ffmpeg is not runnable: " + e.getMessage(), e);
        } finally {
            TempDirs.deleteQuietly(workDir);
        }
    }
}
