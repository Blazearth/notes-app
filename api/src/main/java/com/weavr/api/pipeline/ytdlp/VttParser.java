package com.weavr.api.pipeline.ytdlp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a WebVTT subtitle file into plain prose.
 *
 * <p>Auto-generated captions are the messy case and the common one. YouTube's
 * rolling-window format repeats each line across consecutive cues so the text
 * appears to scroll, which means a naive strip yields every sentence two or
 * three times — and that padding goes straight into the Gemini prompt. Tokens
 * are cheap, but tripled transcripts also bury the actual content.
 */
public final class VttParser {

    private static final Pattern TIMESTAMP_LINE =
            Pattern.compile("^\\s*(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{3}\\s*-->.*$");

    /** Inline karaoke timings and styling, e.g. {@code <00:00:01.234><c>word</c>}. */
    private static final Pattern INLINE_TAG = Pattern.compile("<[^>]*>");

    private static final Pattern CUE_NUMBER = Pattern.compile("^\\s*\\d+\\s*$");

    private VttParser() {
    }

    public static String toPlainText(String vtt) {
        if (vtt == null || vtt.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String raw : vtt.split("\\R")) {
            String line = raw.strip();

            if (line.isEmpty()
                    || line.startsWith("WEBVTT")
                    || line.startsWith("Kind:")
                    || line.startsWith("Language:")
                    || line.startsWith("NOTE")
                    || line.startsWith("STYLE")
                    || CUE_NUMBER.matcher(line).matches()
                    || TIMESTAMP_LINE.matcher(line).matches()) {
                continue;
            }

            line = INLINE_TAG.matcher(line).replaceAll("").strip();
            line = unescape(line);
            if (line.isEmpty()) {
                continue;
            }

            // Drop the rolling-window duplicates: a line identical to one we
            // just kept is the previous cue scrolling, not new speech.
            if (!lines.isEmpty() && lines.getLast().equalsIgnoreCase(line)) {
                continue;
            }
            lines.add(line);
        }

        return collapse(lines);
    }

    /**
     * The rolling window also emits *prefixes*: "hello there" then
     * "hello there my friend". Keeping only the longer one recovers the sentence
     * without inventing text.
     */
    private static String collapse(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!kept.isEmpty()) {
                String previous = kept.getLast();
                if (line.toLowerCase().startsWith(previous.toLowerCase())) {
                    kept.set(kept.size() - 1, line);
                    continue;
                }
                if (previous.toLowerCase().startsWith(line.toLowerCase())) {
                    continue;
                }
            }
            kept.add(line);
        }
        return String.join(" ", kept).strip();
    }

    private static String unescape(String line) {
        return line.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
