package com.weavr.extraction.ytdlp;

/**
 * yt-dlp ran and refused. Carries the raw stderr so {@link YtDlpErrors} can
 * decide whether it is worth retrying — the exit code alone never says.
 * Ported unchanged from {@code api}.
 */
public class YtDlpFailedException extends RuntimeException {

    private final String stderr;

    public YtDlpFailedException(String message, String stderr) {
        super(message);
        this.stderr = stderr == null ? "" : stderr;
    }

    public String stderr() {
        return stderr;
    }
}
