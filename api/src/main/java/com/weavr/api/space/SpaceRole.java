package com.weavr.api.space;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.weavr.api.common.DbEnum;

/**
 * A member's role in a Space.
 *
 * <p>A three-value enum rather than Linkwarden's capability booleans
 * ({@code canCreate}, {@code canUpdate}, {@code canDelete}), which the teardown
 * flagged as a do-not-adopt: every new capability becomes another column and
 * another migration, and the combinations that result are mostly nonsense
 * nobody intends to grant. Three named roles cover what a shared Space actually
 * needs, and a fourth would be one enum constant.
 *
 * <p>{@link #rank()} is what makes "at least editor" expressible without a
 * switch at every call site.
 */
public enum SpaceRole implements DbEnum {

    VIEWER("viewer", 0),
    EDITOR("editor", 1),
    OWNER("owner", 2);

    private final String db;
    private final int rank;

    SpaceRole(String db, int rank) {
        this.db = db;
        this.rank = rank;
    }

    @Override
    public String db() {
        return db;
    }

    public int rank() {
        return rank;
    }

    /** True when this role is {@code required} or stronger. */
    public boolean atLeast(SpaceRole required) {
        return rank >= required.rank;
    }

    @JsonCreator
    public static SpaceRole fromDb(String value) {
        for (SpaceRole role : values()) {
            if (role.db.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown space role: " + value);
    }
}
