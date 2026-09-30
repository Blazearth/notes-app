package com.weavr.api.pipeline.ytdlp;

/**
 * yt-dlp ran and refused. Carries the raw stderr so {@link YtDlpErrors} can
 * decide whether it is worth retrying — the exit code alone never says.
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

    /** True when our own process bound fired, as opposed to yt-dlp exiting with an error. */
    public boolean timedOut() {
        return TIMED_OUT_MESSAGE.equals(getMessage());
    }

    /** What {@code YtDlpClient.describe} says for a timed-out run; the one place both sides agree on it. */
    static final String TIMED_OUT_MESSAGE = "yt-dlp timed out";
}
