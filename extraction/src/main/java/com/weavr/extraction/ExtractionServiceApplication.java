package com.weavr.extraction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The dedicated media extraction service — docs/extraction-architecture.md
 * Part D / Phase 3. A private, backend-only worker: URL validation, provider
 * and yt-dlp routing, captions, ffmpeg keyframe cutting and tesseract OCR.
 * Everything that touches a model (Gemini, Groq) or the database stays in
 * {@code api}; this process owns only the media bytes.
 *
 * <p>{@code @EnableScheduling} backs {@link com.weavr.extraction.artifact.ArtifactStore}'s
 * periodic sweep of expired artifacts — the bounded-disk guarantee every
 * temp-file-writing class in this codebase carries.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ExtractionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractionServiceApplication.class, args);
    }
}
