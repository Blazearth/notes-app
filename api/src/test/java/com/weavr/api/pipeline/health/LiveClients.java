package com.weavr.api.pipeline.health;

import java.net.http.HttpClient;
import java.time.Duration;

import com.weavr.api.pipeline.ExternalProcess;
import com.weavr.api.pipeline.youtube.YouTubeDataApiClient;
import com.weavr.api.pipeline.youtube.YouTubeDataApiProperties;
import com.weavr.api.pipeline.ytdlp.RapidYtClient;
import com.weavr.api.pipeline.ytdlp.RapidYtProperties;
import com.weavr.api.pipeline.ytdlp.YtDlpClient;
import com.weavr.api.pipeline.ytdlp.YtDlpProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real, unmocked clients for the opt-in live tests — built the way production
 * builds them, from the same environment variables. A provider whose key isn't
 * in the environment is constructed disabled, and the live tests report it as
 * not run rather than substituting a mock.
 */
public final class LiveClients {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private LiveClients() {
    }

    public static YtDlpClient ytDlp() {
        // Production defaults (application.yml), no cookies: the canary must not depend on them.
        return new YtDlpClient(new ExternalProcess(), MAPPER, new YtDlpProperties(
                System.getenv().getOrDefault("WEAVR_YTDLP_BINARY", "yt-dlp"),
                Duration.ofSeconds(60), Duration.ofSeconds(90), Duration.ofSeconds(120), Duration.ofSeconds(180),
                null, null, null, null));
    }

    public static RapidYtClient rapidYt() {
        return new RapidYtClient(new RapidYtProperties(System.getenv("WEAVR_RAPID_YT_API_KEY"), null),
                builder(Duration.ofSeconds(15)), MAPPER);
    }

    public static YouTubeDataApiClient youtubeData() {
        return new YouTubeDataApiClient(new YouTubeDataApiProperties(System.getenv("WEAVR_YOUTUBE_API_KEY"), null),
                builder(Duration.ofSeconds(10)), MAPPER);
    }

    private static RestClient.Builder builder(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
