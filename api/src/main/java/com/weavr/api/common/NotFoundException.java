package com.weavr.api.common;

/**
 * Also thrown when a row exists but belongs to someone else — the API must not
 * leak the difference between "no such save" and "not yours".
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
