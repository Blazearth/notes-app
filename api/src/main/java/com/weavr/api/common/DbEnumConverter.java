package com.weavr.api.common;

import java.util.Arrays;

import jakarta.persistence.AttributeConverter;

/**
 * Base for the per-enum JPA converters. Subclasses exist only to pin the type
 * argument, which is what lets {@code autoApply} target a specific enum.
 */
public abstract class DbEnumConverter<E extends Enum<E> & DbEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected DbEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.db();
    }

    @Override
    public E convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(c -> c.db().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown %s value in database: '%s'".formatted(type.getSimpleName(), dbValue)));
    }
}
