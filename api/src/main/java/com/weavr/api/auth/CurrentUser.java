package com.weavr.api.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the authenticated user's id (the JWT {@code sub} claim) to a
 * {@link java.util.UUID} controller parameter.
 *
 * <p>Every authorization decision in the service layer keys off this value.
 * Controllers must never take a user id from the request body or a path
 * variable.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
