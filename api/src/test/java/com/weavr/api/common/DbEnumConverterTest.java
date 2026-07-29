package com.weavr.api.common;

import com.weavr.api.save.LifecycleStatus;
import com.weavr.api.save.SaveStatus;
import com.weavr.api.save.SourceType;
import jakarta.persistence.AttributeConverter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database check constraints spell these lower-case. If Java ever starts
 * writing {@code Enum.name()} instead, every insert fails at the constraint —
 * so pin the mapping in both directions.
 */
class DbEnumConverterTest {

    private final ObjectMapper json = new ObjectMapper();

    private static class SaveStatusConverter extends DbEnumConverter<SaveStatus> {
        SaveStatusConverter() {
            super(SaveStatus.class);
        }
    }

    @Test
    void writesLowerCaseToTheDatabase() {
        AttributeConverter<SaveStatus, String> converter = new SaveStatusConverter();

        assertThat(converter.convertToDatabaseColumn(SaveStatus.PROCESSING)).isEqualTo("processing");
        assertThat(converter.convertToDatabaseColumn(SaveStatus.READY)).isEqualTo("ready");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void readsLowerCaseBackFromTheDatabase() {
        AttributeConverter<SaveStatus, String> converter = new SaveStatusConverter();

        assertThat(converter.convertToEntityAttribute("pending")).isEqualTo(SaveStatus.PENDING);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void failsLoudlyOnAnUnknownDatabaseValue() {
        AttributeConverter<SaveStatus, String> converter = new SaveStatusConverter();

        assertThatThrownBy(() -> converter.convertToEntityAttribute("READY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SaveStatus");
    }

    @Test
    void everyConstantMatchesItsCheckConstraintSpelling() {
        for (SaveStatus s : SaveStatus.values()) {
            assertThat(s.db()).isEqualTo(s.name().toLowerCase());
        }
        for (SourceType s : SourceType.values()) {
            assertThat(s.db()).isEqualTo(s.name().toLowerCase());
        }
        for (LifecycleStatus s : LifecycleStatus.values()) {
            assertThat(s.db()).isEqualTo(s.name().toLowerCase());
        }
    }

    @Test
    void serialisesToLowerCaseJsonToo() throws Exception {
        assertThat(json.writeValueAsString(SaveStatus.PROCESSING)).isEqualTo("\"processing\"");
        assertThat(json.readValue("\"image\"", SourceType.class)).isEqualTo(SourceType.IMAGE);
    }
}
