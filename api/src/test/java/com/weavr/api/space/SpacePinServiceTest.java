package com.weavr.api.space;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.ForbiddenException;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.sync.TombstoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S4 — pins ({@code docs/knowledge-spaces.md}).
 *
 * <p>Same shape and same caveat as {@code EntityCommentServiceTest}: with no
 * local Postgres, what is covered here is the authorisation and the guards
 * around the write, not the SQL itself.
 */
class SpacePinServiceTest {

    private static final UUID SPACE = UUID.randomUUID();
    private static final UUID ARYAN = UUID.randomUUID();
    private static final UUID SAVE = UUID.randomUUID();

    private JdbcClient jdbc;
    private SpaceService spaces;
    private TombstoneService tombstones;
    private SpacePinService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        spaces = mock(SpaceService.class);
        tombstones = mock(TombstoneService.class);
        service = new SpacePinService(jdbc, spaces, JsonMapper.builder().build(), tombstones);
    }

    /**
     * Pinning changes what everybody in the Space sees, so it takes
     * {@code editor} — the same bar as adding content. A viewer must not reach
     * the database at all.
     */
    @Test
    void aViewerCannotPin() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR))
                .thenThrow(new ForbiddenException("You need to be editor or above"));

        assertThatThrownBy(() -> service.pin(ARYAN, SPACE, SpacePinService.SAVE, SAVE.toString(), Map.of()))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(jdbc);
    }

    /**
     * Unpinning takes {@code editor} too, deliberately — not "whoever pinned
     * it". A stale pin only its author can remove leaves a Space stuck showing
     * a program that author stopped doing months ago.
     */
    @Test
    void aViewerCannotUnpin() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR))
                .thenThrow(new ForbiddenException("You need to be editor or above"));

        assertThatThrownBy(() -> service.unpin(ARYAN, SPACE, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(jdbc);
    }

    /**
     * A {@code save} pin points at a row, so it is checked — pinning a save that
     * is not in this Space would put a private save's title on a shared screen.
     * Nothing is inserted when the check fails.
     */
    @Test
    void pinningASaveThatIsNotInThisSpaceIsRejectedBeforeAnyInsert() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR)).thenReturn(SpaceRole.EDITOR);
        stubSaveLookup(false);
        JdbcClient.StatementSpec insert = stubInsert();

        assertThatThrownBy(() -> service.pin(ARYAN, SPACE, SpacePinService.SAVE, SAVE.toString(), Map.of()))
                .isInstanceOf(NotFoundException.class);

        verify(insert, never()).query(any(Class.class));
    }

    /** A subject that is not a uuid at all cannot be a save pin, and says so rather than 500ing. */
    @Test
    void aSavePinWithANonUuidSubjectIsRejected() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR)).thenReturn(SpaceRole.EDITOR);

        assertThatThrownBy(() ->
                service.pin(ARYAN, SPACE, SpacePinService.SAVE, "recommendation_list~anime", Map.of()))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * A {@code collection} pin's subject is a derived node id that references no
     * row anywhere, so there is nothing to check — and checking it against the
     * merge would make pinning cost a full derivation and still be a race. The
     * write must therefore not go looking for a save.
     */
    @Test
    void aCollectionPinIsNotCheckedAgainstSaves() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR)).thenReturn(SpaceRole.EDITOR);
        JdbcClient.StatementSpec lookup = stubSaveLookup(false);
        stubInsert();
        when(spaces.requireMember(ARYAN, SPACE)).thenReturn(SpaceRole.EDITOR);
        stubList();

        assertThatThrownBy(() ->
                service.pin(ARYAN, SPACE, SpacePinService.COLLECTION, "recommendation_list~anime", Map.of()))
                // The pin insert is stubbed to return an id no `list` row
                // carries, so the "vanished after insert" guard fires — which is
                // fine here: what this test pins is that the *save lookup* never
                // ran, below.
                .isInstanceOf(IllegalStateException.class);

        verify(lookup, never()).query(any(Class.class));
    }

    /** A pin that removed no row is gone already, and writes no tombstone for a delete that did not happen. */
    @Test
    void unpinningSomethingAlreadyGoneIsANotFoundWithNoTombstone() {
        when(spaces.requireRole(ARYAN, SPACE, SpaceRole.EDITOR)).thenReturn(SpaceRole.EDITOR);
        when(tombstones.membersOf(SPACE)).thenReturn(List.of(ARYAN));
        JdbcClient.StatementSpec delete = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.startsWith("delete from space_pins"))))
                .thenReturn(delete);
        when(delete.param(any())).thenReturn(delete);
        when(delete.update()).thenReturn(0);

        assertThatThrownBy(() -> service.unpin(ARYAN, SPACE, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);

        verify(tombstones, never()).recordFor(anyList(), eq(TombstoneService.SPACE_PIN), anyString());
    }

    // ------------------------------------------------------------------ glue

    @SuppressWarnings("unchecked")
    private JdbcClient.StatementSpec stubSaveLookup(boolean present) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.startsWith("select 1 from saves"))))
                .thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        JdbcClient.MappedQuerySpec<Integer> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(Integer.class)).thenReturn(mapped);
        when(mapped.optional()).thenReturn(present ? java.util.Optional.of(1) : java.util.Optional.empty());
        return spec;
    }

    @SuppressWarnings("unchecked")
    private JdbcClient.StatementSpec stubInsert() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("insert into space_pins"))))
                .thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        JdbcClient.MappedQuerySpec<UUID> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(UUID.class)).thenReturn(mapped);
        when(mapped.single()).thenReturn(UUID.randomUUID());
        return spec;
    }

    @SuppressWarnings("unchecked")
    private void stubList() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(argThat(sql -> sql != null && sql.contains("from space_pins p"))))
                .thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        JdbcClient.MappedQuerySpec<SpacePinService.Pin> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(List.of());
    }
}
