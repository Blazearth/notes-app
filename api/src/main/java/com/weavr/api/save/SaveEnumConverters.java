package com.weavr.api.save;

import com.weavr.api.common.DbEnumConverter;
import jakarta.persistence.Converter;

/** JPA converters for the save enums. Grouped because each is three lines. */
final class SaveEnumConverters {

    private SaveEnumConverters() {
    }

    @Converter(autoApply = true)
    public static class SaveStatusConverter extends DbEnumConverter<SaveStatus> {
        public SaveStatusConverter() {
            super(SaveStatus.class);
        }
    }

    @Converter(autoApply = true)
    public static class SourceTypeConverter extends DbEnumConverter<SourceType> {
        public SourceTypeConverter() {
            super(SourceType.class);
        }
    }

    @Converter(autoApply = true)
    public static class LifecycleStatusConverter extends DbEnumConverter<LifecycleStatus> {
        public LifecycleStatusConverter() {
            super(LifecycleStatus.class);
        }
    }
}
