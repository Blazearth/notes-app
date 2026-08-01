package com.weavr.api.common;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.lang.NonNull;

/**
 * Binds {@link DbEnum} query parameters and path variables by their
 * {@link DbEnum#db()} spelling.
 *
 * <p>Spring's stock enum converter is {@code Enum.valueOf}, which is
 * case-sensitive and matches {@link Enum#name()} — so {@code ?lifecycle=planned}
 * would be rejected while {@code PLANNED} was accepted, the exact opposite of
 * what the rest of the API speaks. {@link DbEnumConverter} already does this for
 * JPA and {@code @JsonValue}/{@code @JsonCreator} for request bodies; this is
 * the third and last edge.
 */
public class DbEnumConverterFactory implements ConverterFactory<String, DbEnum> {

    @Override
    @NonNull
    public <T extends DbEnum> Converter<String, T> getConverter(@NonNull Class<T> targetType) {
        T[] constants = targetType.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(targetType + " is not an enum");
        }
        return source -> {
            for (T constant : constants) {
                if (constant.db().equalsIgnoreCase(source)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown " + targetType.getSimpleName() + ": " + source);
        };
    }
}
