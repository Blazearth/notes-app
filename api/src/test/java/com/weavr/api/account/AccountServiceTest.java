package com.weavr.api.account;

import java.util.List;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.config.SupabaseAdminClient;
import com.weavr.api.save.Save;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.SaveService;
import com.weavr.api.save.SourceType;
import com.weavr.api.space.SpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcClient} is mocked (no local Postgres — see CLAUDE.md), so these
 * pin the orchestration — which paths run, in what order, and that "already
 * gone" never blocks a retry — not the database's own behaviour.
 */
class AccountServiceTest {

    private JdbcClient jdbc;
    private SpaceService spaces;
    private SaveService saveService;
    private SaveRepository saveRepository;
    private SupabaseAdminClient supabaseAdmin;
    private AccountService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        spaces = mock(SpaceService.class);
        saveService = mock(SaveService.class);
        saveRepository = mock(SaveRepository.class);
        supabaseAdmin = mock(SupabaseAdminClient.class);
        service = new AccountService(jdbc, spaces, saveService, saveRepository, supabaseAdmin);

        stubList("select id from spaces where owner_id", List.of());
        stubList("select space_id from space_members where user_id", List.of());
        when(saveRepository.findByUserId(userId)).thenReturn(List.of());
        stubUpdate("delete from profiles");
    }

    @SuppressWarnings("unchecked")
    private void stubList(String sqlFragment, List<UUID> result) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains(sqlFragment))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        JdbcClient.MappedQuerySpec<UUID> mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(UUID.class)).thenReturn(mapped);
        when(mapped.list()).thenReturn(result);
    }

    private JdbcClient.StatementSpec stubUpdate(String sqlFragment) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains(sqlFragment))).thenReturn(spec);
        when(spec.param(any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        return spec;
    }

    private static Save saveWithId(UUID id) throws ReflectiveOperationException {
        Save save = Save.accepted(UUID.randomUUID(), SourceType.URL, "https://example.com/x", null, null, null);
        var field = Save.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(save, id);
        return save;
    }

    @Test
    void deletesEveryOwnedSpaceThroughSpaceServiceSoTombstonesFire() {
        UUID space1 = UUID.randomUUID();
        UUID space2 = UUID.randomUUID();
        stubList("select id from spaces where owner_id", List.of(space1, space2));

        service.deleteAccount(userId);

        verify(spaces).delete(userId, space1);
        verify(spaces).delete(userId, space2);
    }

    @Test
    void deletesEverySaveThroughSaveServiceEvenInOthersSpaces() throws Exception {
        UUID saveId1 = UUID.randomUUID();
        UUID saveId2 = UUID.randomUUID();
        when(saveRepository.findByUserId(userId))
                .thenReturn(List.of(saveWithId(saveId1), saveWithId(saveId2)));

        service.deleteAccount(userId);

        verify(saveService).delete(userId, saveId1);
        verify(saveService).delete(userId, saveId2);
    }

    @Test
    void leavesMembershipsInSpacesItDoesNotOwn() {
        UUID spaceId = UUID.randomUUID();
        stubList("select space_id from space_members where user_id", List.of(spaceId));

        service.deleteAccount(userId);

        verify(spaces).removeMember(userId, spaceId, userId);
    }

    @Test
    void deletesTheProfileRowAndTheAuthUser() {
        JdbcClient.StatementSpec deleteProfile = stubUpdate("delete from profiles");

        service.deleteAccount(userId);

        verify(deleteProfile).update();
        verify(supabaseAdmin).deleteUser(userId);
    }

    /**
     * A retry after a partial earlier failure must finish the job rather than
     * error on rows the first attempt already removed — every loop tolerates
     * {@link NotFoundException} instead of propagating it.
     */
    @Test
    void alreadyGoneSpacesSavesAndMembershipsDoNotBlockTheRestOfTheDeletion() throws Exception {
        UUID space1 = UUID.randomUUID();
        UUID space2 = UUID.randomUUID();
        stubList("select id from spaces where owner_id", List.of(space1, space2));
        doThrow(new NotFoundException("gone")).when(spaces).delete(userId, space1);

        UUID saveId1 = UUID.randomUUID();
        UUID saveId2 = UUID.randomUUID();
        when(saveRepository.findByUserId(userId))
                .thenReturn(List.of(saveWithId(saveId1), saveWithId(saveId2)));
        doThrow(new NotFoundException("gone")).when(saveService).delete(userId, saveId1);

        UUID memberSpace = UUID.randomUUID();
        stubList("select space_id from space_members where user_id", List.of(memberSpace));
        doThrow(new NotFoundException("gone")).when(spaces).removeMember(userId, memberSpace, userId);

        service.deleteAccount(userId);

        verify(spaces).delete(userId, space2);
        verify(saveService).delete(userId, saveId2);
        verify(supabaseAdmin).deleteUser(userId);
    }

    /**
     * Spaces and saves are gone (and their tombstones fired) before the
     * profile row cascades away the rest, and the Supabase auth user is
     * deleted last of all — every loop above depends on the profile still
     * existing (Space ownership, save ownership), so deleting it first would
     * make every guard 404 instead of doing the deletion it exists to record.
     */
    @Test
    void ordersSpacesThenSavesThenProfileThenTheAuthUserLast() throws Exception {
        UUID spaceId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        stubList("select id from spaces where owner_id", List.of(spaceId));
        when(saveRepository.findByUserId(userId)).thenReturn(List.of(saveWithId(saveId)));
        JdbcClient.StatementSpec deleteProfile = stubUpdate("delete from profiles");

        service.deleteAccount(userId);

        InOrder order = inOrder(spaces, saveService, deleteProfile, supabaseAdmin);
        order.verify(spaces).delete(userId, spaceId);
        order.verify(saveService).delete(userId, saveId);
        order.verify(deleteProfile).update();
        order.verify(supabaseAdmin).deleteUser(userId);
    }

    /**
     * The Postgres-side deletion already committed in its own transaction
     * before the admin call runs, so a failure here surfaces as an error the
     * client can retry — but does not roll back, and does not undo, the data
     * that is already gone.
     */
    @Test
    void aFailureDeletingTheAuthUserPropagatesAfterLocalDataIsAlreadyGone() {
        JdbcClient.StatementSpec deleteProfile = stubUpdate("delete from profiles");
        doThrow(new RuntimeException("Supabase unreachable")).when(supabaseAdmin).deleteUser(userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteAccount(userId))
                .isInstanceOf(RuntimeException.class);

        verify(deleteProfile).update();
    }
}
