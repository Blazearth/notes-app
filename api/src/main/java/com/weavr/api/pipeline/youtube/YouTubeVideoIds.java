package com.weavr.api.pipeline.youtube;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that decides "is this a YouTube video, and which one".
 *
 * <p>Structured parse first — host, then path shape, then the {@code v} query
 * parameter wherever it sits — so {@code watch?feature=share&v=ID} and
 * {@code youtube-nocookie.com/embed/ID}, which the original unanchored regex
 * missed, both resolve. The original regex is kept as a fallback so every
 * string it ever accepted still resolves: this widens what counts as YouTube,
 * it never narrows it.
 */
public final class YouTubeVideoIds {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** Path prefixes whose next segment is the video ID. */
    private static final List<String> ID_SEGMENT_PREFIXES = List.of("shorts", "embed", "live", "v", "e");

    /** The pre-existing matcher (RapidYtClient, F8), unchanged. */
    private static final Pattern LEGACY = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private YouTubeVideoIds() {
    }

    /** @return the 11-character video ID, or null if this isn't a YouTube video URL */
    public static String parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String structured = parseStructured(url.strip());
        if (structured != null) {
            return structured;
        }
        Matcher legacy = LEGACY.matcher(url);
        return legacy.find() ? legacy.group(1) : null;
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    private static String parseStructured(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        String[] segments = segments(uri.getRawPath());

        if (host.equals("youtu.be")) {
            return segments.length > 0 ? valid(segments[0]) : null;
        }
        if (!isYouTubeHost(host)) {
            return null;
        }
        if (segments.length >= 1 && segments[0].equals("watch")) {
            return valid(queryParam(uri.getRawQuery(), "v"));
        }
        if (segments.length >= 2 && ID_SEGMENT_PREFIXES.contains(segments[0])) {
            return valid(segments[1]);
        }
        return null;
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
    }

    private static String[] segments(String path) {
        if (path == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String valid(String candidate) {
        return isValidId(candidate) ? candidate : null;
    }
}
