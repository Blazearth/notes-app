package com.weavr.api.save;

import java.util.Set;

import com.weavr.api.save.dto.CreateSaveRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSaveRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> messages(CreateSaveRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void acceptsAUrlShare() {
        var request = new CreateSaveRequest(
                SourceType.URL, "https://www.instagram.com/reel/abc123/", null, null, null);

        assertThat(messages(request)).isEmpty();
    }

    @Test
    void acceptsOnDeviceOcrTextForAnImage() {
        // Screenshots go through on-device OCR before upload, so an image save
        // arrives as text with no URL.
        var request = new CreateSaveRequest(
                SourceType.IMAGE, null, "2 cups flour\n1 tbsp olive oil", null, null);

        assertThat(messages(request)).isEmpty();
    }

    @Test
    void rejectsUrlShareWithoutAUrl() {
        var request = new CreateSaveRequest(SourceType.URL, null, "some caption", null, null);

        assertThat(messages(request)).contains("sourceUrl is required when sourceType is 'url'");
    }

    @Test
    void rejectsTextShareWithoutText() {
        var request = new CreateSaveRequest(SourceType.TEXT, "https://example.com", "   ", null, null);

        assertThat(messages(request)).contains("text is required when sourceType is 'text'");
    }

    @Test
    void rejectsAnEmptyPayload() {
        // Mirrors the saves_has_content check constraint, so the database
        // constraint is never the thing that reports this to a user.
        var request = new CreateSaveRequest(SourceType.URL, null, null, null, null);

        assertThat(messages(request)).contains("one of sourceUrl or text is required");
    }

    @Test
    void requiresASourceType() {
        var request = new CreateSaveRequest(null, "https://example.com", null, null, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
