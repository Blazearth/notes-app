package com.weavr.api.save;

import java.util.Optional;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.job.JobQueue;
import com.weavr.api.profile.ProfileService;
import com.weavr.api.save.dto.CreateSaveRequest;
import com.weavr.api.space.SpaceRole;
import com.weavr.api.space.SpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /v1/saves} idempotency: the share extension's background
 * {@code URLSession} retries a POST on the OS's schedule, so a retried
 * request must land on the same save instead of minting a second one. This
 * is a *request*-level guard, layered under {@link com.weavr.api.job.JobQueue}'s
 * existing job-level dedupe (which only protects against a duplicate job for
 * a save id that already exists — it can't stop a duplicate save id).
 */
class SaveServiceTest {

    private SaveRepository saves;
    private ProfileService profiles;
    private JobQueue jobs;
    private SpaceService spaces;
    private SaveService service;

    @BeforeEach
    void setUp() {
        saves = mock(SaveRepository.class);
        profiles = mock(ProfileService.class);
        jobs = mock(JobQueue.class);
        spaces = mock(SpaceService.class);
        service = new SaveService(saves, profiles, jobs, spaces);

        // @UuidGenerator only assigns `id` on a real flush; simulate that here
        // so create()'s save.getId() (used to build the job payload) isn't null.
        when(saves.save(any())).thenAnswer(inv -> {
            Save save = inv.getArgument(0);
            assignId(save, UUID.randomUUID());
            return save;
        });
    }

    private static void assignId(Save save, UUID id) throws ReflectiveOperationException {
        var field = Save.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(save, id);
    }

    private static CreateSaveRequest urlRequest() {
        return new CreateSaveRequest(SourceType.URL, "https://example.com/reel", null, null);
    }

    /**
     * The hole Spaces opened up, and the reason this check exists at all:
     * {@code spaceId} used to be written straight through from the request
     * body with nothing looking at it, so any authenticated user could drop a
     * save into any Space whose id they had once been shown.
     */
    @Test
    void savingIntoASpaceRequiresMembershipOfIt() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        doThrow(new NotFoundException("Space not found"))
                .when(spaces).requireRole(userId, spaceId, SpaceRole.EDITOR);

        assertThatThrownBy(() -> service.create(userId,
                new CreateSaveRequest(SourceType.URL, "https://example.com/reel", null, spaceId), null))
                .isInstanceOf(NotFoundException.class);

        verify(saves, never()).save(any());
        verify(jobs, never()).enqueueForUser(any(), any(), anyString(), any());
    }

    /** A viewer may read a Space, not add to it — hence editor, not member. */
    @Test
    void savingIntoASpaceNeedsEditorNotJustMembership() {
        UUID userId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();

        service.create(userId,
                new CreateSaveRequest(SourceType.URL, "https://example.com/reel", null, spaceId), null);

        verify(spaces).requireRole(userId, spaceId, SpaceRole.EDITOR);
    }

    @Test
    void aPrivateSaveNeverTouchesTheSpaceGuard() {
        service.create(UUID.randomUUID(), urlRequest(), null);

        verify(spaces, never()).requireRole(any(), any(), any());
    }

    @Test
    void createsANewSaveWhenNoIdempotencyKeyIsSent() {
        UUID userId = UUID.randomUUID();

        Save save = service.create(userId, urlRequest(), null);

        assertThat(save.getIdempotencyKey()).isNull();
        verify(saves, never()).findByUserIdAndIdempotencyKey(any(), any());
        verify(jobs).enqueueForUser(eq("process_save"), any(), anyString(), eq(userId));
    }

    @Test
    void createsANewSaveOnAFreshIdempotencyKey() {
        UUID userId = UUID.randomUUID();
        when(saves.findByUserIdAndIdempotencyKey(userId, "share-abc123")).thenReturn(Optional.empty());

        Save save = service.create(userId, urlRequest(), "share-abc123");

        assertThat(save.getIdempotencyKey()).isEqualTo("share-abc123");
        verify(jobs).enqueueForUser(eq("process_save"), any(), anyString(), eq(userId));
    }

    @Test
    void replayingAKnownIdempotencyKeyReturnsTheExistingSaveWithoutCreatingAnything() {
        UUID userId = UUID.randomUUID();
        Save existing = Save.accepted(userId, SourceType.URL, "https://example.com/reel", null, null, "share-abc123");
        when(saves.findByUserIdAndIdempotencyKey(userId, "share-abc123")).thenReturn(Optional.of(existing));

        Save result = service.create(userId, urlRequest(), "share-abc123");

        assertThat(result).isSameAs(existing);
        verify(saves, never()).save(any());
        verify(jobs, never()).enqueueForUser(any(), any(), any(), any());
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsNoKey() {
        UUID userId = UUID.randomUUID();

        Save save = service.create(userId, urlRequest(), "   ");

        assertThat(save.getIdempotencyKey()).isNull();
        verify(saves, never()).findByUserIdAndIdempotencyKey(any(), any());
    }

    @Test
    void losingARaceOnTheUniqueConstraintReturnsTheWinnersRowInsteadOfFailing() {
        UUID userId = UUID.randomUUID();
        Save winner = Save.accepted(userId, SourceType.URL, "https://example.com/reel", null, null, "share-abc123");
        // First lookup (the pre-check) finds nothing; the insert then loses the
        // race to a concurrent retry, and the recovery lookup finds the winner.
        when(saves.findByUserIdAndIdempotencyKey(userId, "share-abc123"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        // doThrow(), not when(...).thenThrow(): save() is already stubbed from
        // setUp, and when(mock.method()) has to invoke the mock to record the
        // call — which would run the old stub's side effect (assignId on a
        // null argument) before the new stub replaces it.
        doThrow(new DataIntegrityViolationException("duplicate key")).when(saves).save(any());

        Save result = service.create(userId, urlRequest(), "share-abc123");

        assertThat(result).isSameAs(winner);
        verify(saves, times(2)).findByUserIdAndIdempotencyKey(userId, "share-abc123");
        verify(jobs, never()).enqueueForUser(any(), any(), any(), any());
    }

    @Test
    void aConstraintViolationWithNoIdempotencyKeyPropagates() {
        UUID userId = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("some other constraint")).when(saves).save(any());

        assertThatThrownBy(() -> service.create(userId, urlRequest(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settingFavoriteLeavesArchivedUntouched() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        Save save = Save.accepted(userId, SourceType.URL, "https://example.com/reel", null, null, null);
        save.setArchived(true);
        when(saves.findByIdAndUserId(saveId, userId)).thenReturn(Optional.of(save));

        Save result = service.setFlags(userId, saveId, true, null);

        assertThat(result.isFavorite()).isTrue();
        assertThat(result.isArchived()).isTrue();
    }

    @Test
    void settingArchivedLeavesFavoriteUntouched() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        Save save = Save.accepted(userId, SourceType.URL, "https://example.com/reel", null, null, null);
        save.setFavorite(true);
        when(saves.findByIdAndUserId(saveId, userId)).thenReturn(Optional.of(save));

        Save result = service.setFlags(userId, saveId, null, true);

        assertThat(result.isFavorite()).isTrue();
        assertThat(result.isArchived()).isTrue();
    }

    @Test
    void settingFlagsOnAnUnknownSaveThrowsNotFound() {
        UUID userId = UUID.randomUUID();
        UUID saveId = UUID.randomUUID();
        when(saves.findByIdAndUserId(saveId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setFlags(userId, saveId, true, null))
                .isInstanceOf(NotFoundException.class);
    }
}
