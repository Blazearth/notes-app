package com.weavr.api.common;

/**
 * The request itself is malformed in a way bean validation cannot express, and
 * no amount of retrying will change that.
 *
 * <p>Deliberately not {@link IllegalArgumentException}: a handler for that would
 * turn every library-thrown argument error anywhere in the stack into a 400,
 * which is how a genuine server bug ends up reported as the caller's mistake.
 * A named exception is thrown only where a 400 is actually meant.
 *
 * <p>400 matters to the client beyond the wording — {@code ApiError} maps it to
 * {@code validation}, which the outbox treats as terminal and <em>surfaces</em>
 * rather than retrying forever (see {@code app/src/local/outbox.ts}).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
