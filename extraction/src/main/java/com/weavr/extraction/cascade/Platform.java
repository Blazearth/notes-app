package com.weavr.extraction.cascade;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Coarse platform detection from a URL's host, for the wire metadata's {@code platform} field. */
final class Platform {

    private Platform() {
    }

    static String detect(String url) {
        String host = host(url);
        if (host == null) {
            return "web";
        }
        if (host.endsWith("youtube.com") || host.equals("youtu.be")) {
            return "youtube";
        }
        if (host.endsWith("tiktok.com")) {
            return "tiktok";
        }
        if (host.endsWith("instagram.com")) {
            return "instagram";
        }
        return "web";
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
