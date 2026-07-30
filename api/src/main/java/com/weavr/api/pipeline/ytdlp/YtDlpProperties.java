package com.weavr.api.pipeline.ytdlp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param binary        executable name or absolute path. The Docker image must
 *                      install it; a plain JRE base image has neither yt-dlp nor
 *                      ffmpeg
 * @param probeTimeout  bound on the metadata probe, which does no downloading
 * @param captionTimeout bound on the caption fetch
 * @param subtitleLangs `--sub-langs` value. Exact codes, never a regex — see
 *                      the constructor default
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
        // Exact codes. `--sub-langs` treats each entry as a regex, and yt-dlp
        // synthesises translated tracks named `<source>-<target>` at download
        // time — so "en.*" matches en-ar, en-ja, en-ru and every other
        // translation target. Measured against one real TED talk that is 29
        // subtitle downloads for one video, which earns an HTTP 429 partway
        // through and leaves the fetch half-done.
        //
        // "en" is the English track (yt-dlp prefers an uploaded one over a
        // machine caption); "en-orig" is the original-language track when that
        // language is English.
        if (subtitleLangs == null || subtitleLangs.isBlank()) subtitleLangs = "en,en-orig";
    }
}
