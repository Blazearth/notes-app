package com.weavr.api.pipeline.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param binary        executable name or absolute path. The Docker image must
 *                      install it; a plain JRE base image has neither yt-dlp nor
 *                      ffmpeg
 * @param probeTimeout  bound on the metadata probe, which does no downloading
 * @param captionTimeout bound on the caption fetch
 * @param subtitleLangs `--sub-langs` value. Defaults to English variants; the
 *                      probe reports what a video actually offers
 */
@ConfigurationProperties(prefix = "weavr.ytdlp")
public record YtDlpProperties(
        String binary,
        Duration probeTimeout,
        Duration captionTimeout,
        String subtitleLangs
) {

    public YtDlpProperties {
        if (binary == null || binary.isBlank()) binary = "yt-dlp";
        if (probeTimeout == null) probeTimeout = Duration.ofSeconds(60);
        if (captionTimeout == null) captionTimeout = Duration.ofSeconds(90);
        if (subtitleLangs == null || subtitleLangs.isBlank()) subtitleLangs = "en.*,en";
    }
}
