package com.weavr.api.common;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enums that persist as lower-case text rather than {@link Enum#name()}.
 *
 * <p>The database stores lower-case ({@code processing}, {@code ready}) because
 * the check constraints and every hand-written pipeline query read better that
 * way, and the JSON API exposes the same spelling. Java keeps the conventional
 * upper-case constants. {@link DbEnumConverter} bridges the two for JPA;
 * {@link JsonValue} on {@link #db()} does the same for Jackson.
 */
public interface DbEnum {

    @JsonValue
    String db();
}
