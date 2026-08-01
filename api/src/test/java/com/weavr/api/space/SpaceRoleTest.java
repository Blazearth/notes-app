package com.weavr.api.space;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The role ladder every authorisation check in {@link SpaceService} is
 * expressed against.
 *
 * <p>Small, but it is the piece where an inverted comparison would silently
 * grant a viewer an owner's powers, and nothing else in the codebase would
 * notice.
 */
class SpaceRoleTest {

    @Test
    void ownerOutranksEditorOutranksViewer() {
        assertThat(SpaceRole.OWNER.atLeast(SpaceRole.EDITOR)).isTrue();
        assertThat(SpaceRole.OWNER.atLeast(SpaceRole.VIEWER)).isTrue();
        assertThat(SpaceRole.EDITOR.atLeast(SpaceRole.VIEWER)).isTrue();
    }

    @Test
    void aRoleSatisfiesItself() {
        for (SpaceRole role : SpaceRole.values()) {
            assertThat(role.atLeast(role)).as("%s should satisfy itself", role).isTrue();
        }
    }

    /** The direction that matters: a viewer must never pass an editor check. */
    @Test
    void aLesserRoleNeverSatisfiesAGreaterOne() {
        assertThat(SpaceRole.VIEWER.atLeast(SpaceRole.EDITOR)).isFalse();
        assertThat(SpaceRole.VIEWER.atLeast(SpaceRole.OWNER)).isFalse();
        assertThat(SpaceRole.EDITOR.atLeast(SpaceRole.OWNER)).isFalse();
    }

    /** Lower-case in the database and on the wire, upper-case in Java — as everywhere else. */
    @Test
    void persistsAndSerialisesAsLowerCase() {
        assertThat(SpaceRole.OWNER.db()).isEqualTo("owner");
        assertThat(SpaceRole.fromDb("viewer")).isEqualTo(SpaceRole.VIEWER);
    }

    @Test
    void rejectsAnUnknownRole() {
        assertThatThrownBy(() -> SpaceRole.fromDb("admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin");
        assertThatThrownBy(() -> SpaceRole.fromDb("OWNER"))
                .as("upper case is Java's spelling, not the database's")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
