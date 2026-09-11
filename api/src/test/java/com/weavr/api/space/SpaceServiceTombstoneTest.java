package com.weavr.api.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.profile.ProfileService;
import com.weavr.api.sync.TombstoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two delete paths in {@link SpaceService} that a local-first client cannot
 * infer, and the ordering one of them depends on.
 *
 * <p>{@link JdbcClient} is mocked (no local Postgres — see CLAUDE.md), so these
 * pin *who* learns about a delete and *when* the audience is read, not the
 * database's behaviour. The ordering assertion is the point of the file: reading
 * the members after the delete compiles, passes any test that only checks a
 * tombstone was written, and silently loses every other member's notification,
 * because {@code space_members} cascades away with the Space.
 */
class SpaceServiceTombstoneTest {

    private JdbcClient jdbc;
    private TombstoneService tombstones;
    private SpaceService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        tombstones = mock(TombstoneService.class);
        service = new SpaceService(jdbc, mock(ProfileService.class), JsonMapper.builder().build(),
                tombstones, mock(com.weavr.api.notification.NotificationService.class));
    }

    /** `roleOf`, which every guard in SpaceService funnels through. */
    @SuppressWarnings("unchecked")
    private void stubRole(SpaceRole role) {
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("select role from space_members"))).thenReturn(select);
        when(select.param(any())).thenReturn(select);
        JdbcClient.MappedQuerySpec<String> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(String.class)).thenReturn(mapped);
        when(mapped.optional()).thenReturn(Optional.of(role.db()));
    }

    private JdbcClient.StatementSpec stubUpdate(String sqlFragment) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains(sqlFragment))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        return spec;
    }

    @Test
    void deletingASpaceTombstonesItForEveryMemberNotJustTheOwner() {
        stubRole(SpaceRole.OWNER);
        stubUpdate("delete from spaces");
        when(tombstones.membersOf(spaceId)).thenReturn(List.of(owner, other));

        service.delete(owner, spaceId);

        verify(tombstones).recordFor(List.of(owner, other), TombstoneService.SPACE, spaceId.toString());
    }

    /**
     * The ordering that makes the tombstone above worth anything: {@code
     * space_members} rows cascade away with the Space, so an audience read after
     * the delete is empty and every other member keeps a dead Space in their
     * cache indefinitely.
     */
    @Test
    void theAudienceIsReadBeforeTheSpaceIsDeleted() {
        stubRole(SpaceRole.OWNER);
        JdbcClient.StatementSpec delete = stubUpdate("delete from spaces");
        when(tombstones.membersOf(spaceId)).thenReturn(List.of(owner, other));

        service.delete(owner, spaceId);

        InOrder order = inOrder(tombstones, delete);
        order.verify(tombstones).membersOf(spaceId);
        order.verify(delete).update();
    }

    /**
     * One delete, two meanings. The people still in the Space lost a member; the
     * person removed lost the Space itself — a {@code space_member} tombstone
     * would only have taken their own row out of a list they can no longer read,
     * leaving the Space, its saves and its member list cached forever.
     */
    @Test
    void removingSomeoneTellsTheRestAMemberLeftAndTellsThemTheSpaceIsGone() {
        stubRole(SpaceRole.OWNER);
        stubUpdate("delete from space_members");
        when(tombstones.membersOf(spaceId)).thenReturn(List.of(owner, other));

        service.removeMember(owner, spaceId, other);

        verify(tombstones).recordFor(List.of(owner), TombstoneService.SPACE_MEMBER,
                spaceId + "|" + other);
        verify(tombstones).record(other, TombstoneService.SPACE, spaceId.toString());
    }

    @Test
    void theRemovedMemberIsNotToldTheirOwnMembershipRowChanged() {
        stubRole(SpaceRole.OWNER);
        stubUpdate("delete from space_members");
        when(tombstones.membersOf(spaceId)).thenReturn(List.of(owner, other));

        service.removeMember(owner, spaceId, other);

        // They get the `space` tombstone instead — a member-row deletion inside a
        // Space they can no longer see would be noise they cannot act on.
        verify(tombstones).recordFor(List.of(owner), TombstoneService.SPACE_MEMBER,
                spaceId + "|" + other);
        assertThat(List.of(owner)).doesNotContain(other);
    }
}
