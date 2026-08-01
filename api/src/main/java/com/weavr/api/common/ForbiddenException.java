package com.weavr.api.common;

/**
 * The caller may see the resource but not do this to it — a Space viewer trying
 * to edit, say.
 *
 * <p>Distinct from {@link NotFoundException}, which is deliberately also used
 * for "exists but is not yours". The difference is whether the caller already
 * knows the resource exists: a member of a Space does, so hiding it behind a 404
 * would only confuse them.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
