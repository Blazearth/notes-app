package com.weavr.api.space;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.ForbiddenException;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.sync.TombstoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S3 — entity comments ({@code docs/knowledge-spaces.md}).
 *
 * <p>{@link SpaceService} is mocked, so what these cover is the authorisation
 * delegation and the branching around it, not the SQL: there is no local
 * Postgres (CLAUDE.md), and a mocked {@link JdbcClient} chain asserts the query
 * I wrote rather than the query Postgres accepts. The same standing caveat
 * {@code SaveItemStateServiceTest} and {@code EntityStateServiceTest} carry.
 */
class EntityCommentServiceTest {

    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID MAYA = UUID.randomUUID();
    private static final UUID RAHUL = UUID.randomUUID();
    private static final String KEY = "screen:blue box";

    private JdbcClient jdbc;
    private SpaceService spaces;
    private TombstoneService tombstones;
    private EntityCommentService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        spaces = mock(SpaceService.class);
        tombstones = mock(TombstoneService.class);
        service = new EntityCommentService(jdbc, spaces, tombstones);
    }

    /**
     * Membership first, always. A non-member must get the same 404 every other
     * Space read gives them rather than an empty list — an empty list would
     * confirm the Space exists, which is exactly what {@code requireMember}
     * refuses to do.
     */
    @Test
    void aNonMemberNeverReachesTheDatabase() {
        when(spaces.requireMember(MAYA, SPACE)).thenThrow(new NotFoundException("Space not found"));

        assertThatThrownBy(() -> service.list(MAYA, SPACE, KEY))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.add(MAYA, SPACE, KEY, "starts slow"))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(jdbc);
    }

    /** No keys, no query — the same batching contract every other batched read here has. */
    @Test
    void countsForNoEntitiesCostsNoQuery() {
        assertThat(service.counts(SPACE, List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void countsAreReturnedPerEntityAndAbsentWhereNobodyCommented() {
        stubCounts(List.of(Map.entry(KEY, 2)));

        Map<String, Integer> counts = service.counts(SPACE, List.of(KEY, "screen:frieren"));

        assertThat(counts).containsEntry(KEY, 2);
        // Absent rather than zero: a caller reads getOrDefault, and a row per
        // silent entity would be a payload proportional to the collection
        // rather than to the discussion.
        assertThat(counts).doesNotContainKey("screen:frieren");
    }

    /**
     * A member deleting their own comment sends the {@code user_id}-bounded
     * statement; the Space's owner sends the unbounded one. That branch is the
     * whole of the authorisation rule, and it is the same one
     * {@code SaveSocialService.deleteComment} makes.
     */
    @Test
    void anOwnerDeletesAnybodysCommentAndAMemberOnlyTheirOwn() {
        UUID commentId = UUID.randomUUID();
        when(tombstones.membersOf(SPACE)).thenReturn(List.of(MAYA, RAHUL));

        JdbcClient.StatementSpec ownerDelete = stubDelete(false, 1);
        JdbcClient.StatementSpec ownDelete = stubDelete(true, 1);

        when(spaces.requireMember(MAYA, SPACE)).thenReturn(SpaceRole.OWNER);
        service.delete(MAYA, SPACE, commentId);
        verify(ownerDelete).update();
        verify(ownDelete, never()).update();

        when(spaces.requireMember(RAHUL, SPACE)).thenReturn(SpaceRole.EDITOR);
        service.delete(RAHUL, SPACE, commentId);
        verify(ownDelete).update();
    }

    /**
     * Deleting somebody else's comment as an ordinary member removes no row, and
     * that is a 403 rather than a 404: the caller is looking at the comment in a
     * list they just read, so pretending it does not exist would be nonsense.
     * And no tombstone is written for a delete that did not happen.
     */
    @Test
    void deletingSomebodyElsesCommentIsForbiddenAndWritesNoTombstone() {
        when(spaces.requireMember(RAHUL, SPACE)).thenReturn(SpaceRole.EDITOR);
        when(tombstones.membersOf(SPACE)).thenReturn(List.of(MAYA, RAHUL));
        stubDelete(true, 0);

        assertThatThrownBy(() -> service.delete(RAHUL, SPACE, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);

        verify(tombstones, never()).recordFor(anyList(), eq(TombstoneService.ENTITY_COMMENT), any());
    }

    /**
     * The tombstone is addressed to the Space's members, not to the deleter.
     * A remark removed on one phone has to disappear from the other three, and
     * a record written only for whoever tapped is a record nobody else ever
     * sees — the standing rule that broke `save_comments` deletion for every
     * audience but one.
     */
    @Test
    void deletingWritesATombstoneForEveryMember() {
        UUID commentId = UUID.randomUUID();
        when(spaces.requireMember(MAYA, SPACE)).thenReturn(SpaceRole.OWNER);
        when(tombstones.membersOf(SPACE)).thenReturn(List.of(MAYA, RAHUL));
        stubDelete(false, 1);

        service.delete(MAYA, SPACE, commentId);

        verify(tombstones).recordFor(List.of(MAYA, RAHUL), TombstoneService.ENTITY_COMMENT,
                commentId.toString());
    }

    // ------------------------------------------------------------------ glue

    /**
     * @param scopedToAuthor the member's statement (bounded by {@code user_id})
     *                       rather than the owner's. Matched on the presence of
     *                       that clause rather than on the whole string, so
     *                       reformatting the SQL does not fail the test while
     *                       swapping the two branches still does.
     */
    private JdbcClient.StatementSpec stubDelete(boolean scopedToAuthor, int rows) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(org.mockito.ArgumentMatchers.argThat(sql ->
                sql != null
                        && sql.startsWith("delete from entity_comments")
                        && sql.contains("user_id = ?") == scopedToAuthor)))
                .thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(rows);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private void stubCounts(List<Map.Entry<String, Integer>> rows) {
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("from entity_comments"))).thenReturn(select);
        when(select.param(any())).thenReturn(select);
        JdbcClient.MappedQuerySpec<Map.Entry<String, Integer>> mapped =
                mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(any(RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
    }
}
