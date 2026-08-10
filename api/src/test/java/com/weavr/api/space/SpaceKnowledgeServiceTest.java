package com.weavr.api.space;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.space.SpaceKnowledgeService.MemberProgress;
import com.weavr.api.space.SpaceKnowledgeService.MemberState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2's rollup rules ({@code docs/knowledge-spaces.md}), against fixtures.
 *
 * <p>{@code rollUp} and {@code doneKeys} are static and database-free for
 * exactly this reason — the same call {@code ShoppingListService.fold} and the
 * collection merge core made. The two queries feeding them
 * ({@code memberStates}, {@code recentComments}) are not covered here for the
 * same standing reason {@code SaveItemStateServiceTest} and
 * {@code EntityStateServiceTest} state: there is no local Postgres, and a
 * mocked {@code JdbcClient} chain would assert the SQL I wrote rather than the
 * SQL Postgres accepts.
 */
class SpaceKnowledgeServiceTest {

    private static final UUID MAYA = UUID.randomUUID();
    private static final UUID RAHUL = UUID.randomUUID();
    private static final UUID ARYAN = UUID.randomUUID();

    private static MemberState state(UUID userId, String displayName, Map<String, Object> state) {
        return new MemberState(userId, displayName, state);
    }

    /** K7 writes both keys on every status; a pre-K7 row has `done` alone. */
    private static Map<String, Object> watched() {
        return Map.of("status", "watched", "done", true);
    }

    private static Map<String, Object> watching() {
        return Map.of("status", "watching", "done", false);
    }

    /**
     * The doc's fixture, literally: an entity three members hold in three
     * different states. Nobody's view overwrites anybody else's, and the group
     * count is "anyone has finished it".
     */
    @Test
    void oneEntityHeldByThreeMembersInThreeStates() {
        Map<String, List<MemberState>> states = Map.of("anime:blue box", List.of(
                state(MAYA, "Maya", watched()),
                state(RAHUL, "Rahul", watching()),
                state(ARYAN, "Aryan", Map.of("rating", 4))));

        assertThat(SpaceKnowledgeService.doneKeys(states)).containsExactly("anime:blue box");

        List<MemberProgress> progress = SpaceKnowledgeService.rollUp(states);
        assertThat(progress).hasSize(3);
        assertThat(progress.getFirst().userId()).isEqualTo(MAYA);
        assertThat(progress.getFirst().doneCount()).isEqualTo(1);
        assertThat(progress).filteredOn(p -> p.userId().equals(RAHUL))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.doneCount()).isZero();
                    assertThat(p.inProgressCount()).isEqualTo(1);
                });
    }

    /**
     * "Maya: 5 of 12 watched" is a count across the whole collection, not per
     * entity — so a member's tallies accumulate over every entity they touched.
     */
    @Test
    void aMembersProgressAccumulatesAcrossEveryEntityTheyTouched() {
        Map<String, List<MemberState>> states = new LinkedHashMap<>();
        states.put("anime:blue box", List.of(state(MAYA, "Maya", watched())));
        states.put("anime:frieren", List.of(state(MAYA, "Maya", watched())));
        states.put("anime:horimiya", List.of(state(MAYA, "Maya", watching())));

        List<MemberProgress> progress = SpaceKnowledgeService.rollUp(states);

        assertThat(progress).singleElement().satisfies(p -> {
            assertThat(p.displayName()).isEqualTo("Maya");
            assertThat(p.doneCount()).isEqualTo(2);
            assertThat(p.inProgressCount()).isEqualTo(1);
        });
    }

    /**
     * A row written before K7's statuses existed carries {@code done} alone.
     * {@code done} stayed canonical precisely so this keeps counting — the same
     * back-compat direction {@code currentStatus} protects app-side.
     */
    @Test
    void aPreStatusDoneFlagStillCounts() {
        Map<String, List<MemberState>> states =
                Map.of("anime:blue box", List.of(state(MAYA, "Maya", Map.of("done", true))));

        assertThat(SpaceKnowledgeService.doneKeys(states)).containsExactly("anime:blue box");
        assertThat(SpaceKnowledgeService.rollUp(states).getFirst().doneCount()).isEqualTo(1);
    }

    /**
     * Nobody has finished it, so it is not in the group's done set — but the
     * two members who are part-way through are still reported, which is the
     * whole reason {@link MemberProgress} carries two numbers.
     */
    @Test
    void anEntityNobodyFinishedIsNotDoneButStillShowsProgress() {
        Map<String, List<MemberState>> states = Map.of("anime:blue box", List.of(
                state(MAYA, "Maya", watching()),
                state(RAHUL, "Rahul", watching())));

        assertThat(SpaceKnowledgeService.doneKeys(states)).isEmpty();
        assertThat(SpaceKnowledgeService.rollUp(states))
                .extracting(MemberProgress::inProgressCount)
                .containsExactly(1, 1);
    }

    /**
     * A Space with no entity states at all — a brand-new Space, or one whose
     * members have not touched anything. Empty, not null, and no crash: the
     * Overview renders its zero state from this.
     */
    @Test
    void aSpaceWithNoStatesRollsUpToNothing() {
        assertThat(SpaceKnowledgeService.rollUp(Map.of())).isEmpty();
        assertThat(SpaceKnowledgeService.doneKeys(Map.of())).isEmpty();
    }

    /**
     * A member who left: their {@code entity_states} rows survive (they are
     * global, not Space-scoped) but the join drops them, so nothing here ever
     * sees a state without a member. A null display name is the case that
     * <em>can</em> still arrive — a profile row that never got a name — and it
     * must not sink the rollup.
     */
    @Test
    void aMissingDisplayNameDoesNotSinkTheRollup() {
        Map<String, List<MemberState>> states =
                Map.of("anime:blue box", List.of(state(MAYA, null, watched())));

        assertThat(SpaceKnowledgeService.rollUp(states)).singleElement().satisfies(p -> {
            assertThat(p.displayName()).isNull();
            assertThat(p.doneCount()).isEqualTo(1);
        });
    }
}
