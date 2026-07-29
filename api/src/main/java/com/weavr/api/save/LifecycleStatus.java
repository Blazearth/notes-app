package com.weavr.api.save;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.weavr.api.common.DbEnum;

/** How far the user has got with a save: saved -> planned -> started -> completed. */
public enum LifecycleStatus implements DbEnum {

    SAVED("saved"),
    PLANNED("planned"),
    STARTED("started"),
    COMPLETED("completed");

    private final String db;

    LifecycleStatus(String db) {
        this.db = db;
    }

    @Override
    public String db() {
        return db;
    }

    @JsonCreator
    public static LifecycleStatus fromDb(String value) {
        for (LifecycleStatus s : values()) {
            if (s.db.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown lifecycle_status: " + value);
    }
}
