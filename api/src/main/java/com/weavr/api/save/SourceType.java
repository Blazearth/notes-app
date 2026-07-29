package com.weavr.api.save;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.weavr.api.common.DbEnum;

/**
 * What the client handed us — not what the content turns out to be. The
 * knowledge type is decided later by the pipeline.
 */
public enum SourceType implements DbEnum {

    /** A link from the share sheet: Reel, TikTok, YouTube, article. */
    URL("url"),

    /** Typed or pasted text, and shared plain-text payloads. */
    TEXT("text"),

    /** Screenshot or photo. Carries OCR text extracted on-device. */
    IMAGE("image"),

    PDF("pdf"),

    AUDIO("audio");

    private final String db;

    SourceType(String db) {
        this.db = db;
    }

    @Override
    public String db() {
        return db;
    }

    @JsonCreator
    public static SourceType fromDb(String value) {
        for (SourceType s : values()) {
            if (s.db.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown source_type: " + value);
    }
}
