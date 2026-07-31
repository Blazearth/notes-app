package com.weavr.api.pipeline.audio;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param binary  executable name or absolute path. The Docker image must
 *                install it; a plain JRE base image has neither yt-dlp nor
 *                ffmpeg
 * @param timeout bound on the downmix. Bytes in are already capped by the
 *                yt-dlp duration cap, so this only guards against a stuck
 *                process, not a large input
 */
@ConfigurationProperties(prefix = "weavr.ffmpeg")
public record FfmpegProperties(
        String binary,
        Duration timeout
) {

    public FfmpegProperties {
        if (binary == null || binary.isBlank()) binary = "ffmpeg";
        if (timeout == null) timeout = Duration.ofSeconds(60);
    }
}
