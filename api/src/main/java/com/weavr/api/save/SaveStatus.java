package com.weavr.api.save;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.weavr.api.common.DbEnum;

public enum SaveStatus implements DbEnum {

    /** Accepted, job enqueued, pipeline has not finished. */
    PROCESSING("processing"),

    /**
     * Deliberately parked, not broken. The daily Gemini budget was spent, so
     * the save waits for the next window. The user is told; this is never
     * surfaced as a failure.
     */
    PENDING("pending"),

    READY("ready"),

    FAILED("failed");

    private final String db;

    SaveStatus(String db) {
        this.db = db;
    }

    @Override
    public String db() {
        return db;
    }

    @JsonCreator
    public static SaveStatus fromDb(String value) {
        for (SaveStatus s : values()) {
            if (s.db.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + value);
    }
}
