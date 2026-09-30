package com.weavr.api.pipeline.health;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Host-based platform label for attempt records. Mirrors {@code extraction/}'s
 * own {@code Platform.detect} so the two services report the same names.
 */
public final class SourcePlatform {

    private SourcePlatform() {
    }

    public static String detect(String url) {
        String host = host(url);
        if (host == null) {
            return "web";
        }
        if (host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")) {
            return "youtube";
        }
        if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) {
            return "tiktok";
        }
        if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
            return "instagram";
        }
        return "web";
    }

    private static String host(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = new URI(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
