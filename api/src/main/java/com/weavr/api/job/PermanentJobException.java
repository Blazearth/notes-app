package com.weavr.api.job;

/**
 * This job will never succeed: an unsupported URL, a deleted post, a payload
 * that does not parse. Fails immediately without consuming retries.
 *
 * <p>{@link #userMessage()} is shown to the user, so it must be free of
 * internals — the share extension means nobody is watching when a save fails,
 * and the feed is where they find out.
 */
public class PermanentJobException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;

    public PermanentJobException(String errorCode, String userMessage) {
        this(errorCode, userMessage, null);
    }

    public PermanentJobException(String errorCode, String userMessage, Throwable cause) {
        super(errorCode + ": " + userMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public String errorCode() {
        return errorCode;
    }

    public String userMessage() {
        return userMessage;
    }
}
