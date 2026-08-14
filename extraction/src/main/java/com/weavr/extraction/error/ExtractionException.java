package com.weavr.extraction.error;

/**
 * The one exception type every internal failure eventually becomes.
 * {@link com.weavr.extraction.api.ApiExceptionHandler} is the single place
 * that turns this into the wire error body — no handler anywhere else
 * branches on an error string.
 *
 * <p>{@link #userMessage()} is user-safe prose with no internals — no stack
 * traces, no paths, no upstream URLs — per the doc's own rule: with silent
 * capture, this message is the only failure explanation a user ever sees.
 */
public class ExtractionException extends RuntimeException {

    private final ErrorCode code;
    private final String userMessage;

    public ExtractionException(ErrorCode code, String userMessage) {
        this(code, userMessage, null);
    }

    public ExtractionException(ErrorCode code, String userMessage, Throwable cause) {
        super(code + ": " + userMessage, cause);
        this.code = code;
        this.userMessage = userMessage;
    }

    public ErrorCode code() {
        return code;
    }

    public String userMessage() {
        return userMessage;
    }
}
