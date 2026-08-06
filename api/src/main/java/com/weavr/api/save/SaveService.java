package com.weavr.api.save;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobType;
import com.weavr.api.profile.ProfileService;
import com.weavr.api.save.dto.CreateSaveRequest;
import com.weavr.api.save.dto.SaveResponse;
import com.weavr.api.space.SpaceRole;
import com.weavr.api.space.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Authorization boundary for saves.
 *
 * <p>Spring connects to Postgres as a service role, so RLS does not constrain
 * these queries. Every method here must therefore scope by the caller's id
 * itself — never trust an id from the request.
 */
@Service
public class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final SaveRepository saves;
    private final ProfileService profiles;
    private final JobQueue jobs;
    private final SpaceService spaces;

    SaveService(SaveRepository saves, ProfileService profiles, JobQueue jobs, SpaceService spaces) {
        this.saves = saves;
        this.profiles = profiles;
        this.jobs = jobs;
        this.spaces = spaces;
    }

    /**
     * Accepts a save and hands the work to the queue. Returns as soon as the
     * row is written — nothing in the request path touches yt-dlp, ffmpeg or
     * Gemini.
     *
     * <p>{@code idempotencyKey} makes the whole request idempotent, not just
     * the job behind it: the share extension's background {@code URLSession}
     * retries a POST on its own schedule, and without this a retried request
     * created a second save with a new id. A caller that skips the header
     * (the app's own Paste Link tile, today) gets no dedupe protection —
     * every such call creates a new save, same as before.
     *
     * @param idempotencyKey from the {@code Idempotency-Key} header, or
     *                       {@code null}/blank if the caller sent none
     */
    @Transactional
    public Save create(UUID userId, CreateSaveRequest request, String idempotencyKey) {
        profiles.ensureExists(userId);

        // Until Spaces existed, `spaceId` was written straight through from the
        // request body with nothing checking it — so any authenticated user
        // could drop a save into any Space whose id they could guess or had
        // once been shown. Editor, not member: a viewer is exactly the role
        // that may read a Space without adding to it.
        if (request.spaceId() != null) {
            spaces.requireRole(userId, request.spaceId(), SpaceRole.EDITOR);
        }

        String key = blankToNull(idempotencyKey);
        if (key != null) {
            Optional<Save> existing = saves.findByUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("Idempotent replay of save key={} user={}, returning existing save {}",
                        key, userId, existing.get().getId());
                return existing.get();
            }
        }

        Save save;
        try {
            save = saves.save(Save.accepted(
                    userId,
                    request.sourceType(),
                    blankToNull(request.sourceUrl()),
                    blankToNull(request.text()),
                    request.spaceId(),
                    key));
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent retry carrying the same key —
            // the winner's row is what the client should see, not a 500.
            if (key == null) {
                throw e;
            }
            return saves.findByUserIdAndIdempotencyKey(userId, key).orElseThrow(() -> e);
        }

        // Keyed on the save id, so a retried enqueue is a no-op. This does not
        // dedupe a re-shared URL - that is content-level and lands with the
        // share extension work.
        jobs.enqueueForUser(
                JobType.PROCESS_SAVE,
                Map.of("saveId", save.getId().toString()),
                JobType.PROCESS_SAVE + ":" + save.getId(),
                userId);

        // "Someone added something" is one of the few events worth a line in a
        // shared Space's feed. Deliberately recorded at accept time rather than
        // when the pipeline finishes: the interesting fact is that a person did
        // something, and a card appearing minutes later against an unrelated
        // timestamp reads as a glitch.
        recordSaveAddedAfterCommit(save.getSpaceId(), userId, save.getId());

        log.info("Accepted save id={} type={} user={}", save.getId(), save.getSourceType(), userId);
        return save;
    }

    /**
     * After the commit, not inside it.
     *
     * <p>Found by running this against a real database, not by any test:
     * {@code space_activity.save_id} carries a foreign key, and Hibernate has
     * not issued the {@code saves} INSERT at this point — {@code persist()}
     * with an application-assigned UUID defers it to flush. Writing the
     * activity row first therefore violated the constraint, and because
     * Postgres aborts the whole transaction on a failed statement, the
     * {@code catch} inside {@link SpaceService#recordActivity} could not
     * contain it either: every save into a Space failed with a 500.
     *
     * <p>An after-commit hook is the right answer rather than merely a working
     * one — an activity entry should describe something that actually
     * happened, so a save that rolls back should leave no trace of itself in
     * the feed.
     */
    private void recordSaveAddedAfterCommit(UUID spaceId, UUID userId, UUID saveId) {
        if (spaceId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            spaces.recordActivity(spaceId, userId, saveId, "save_added", Map.of());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                spaces.recordActivity(spaceId, userId, saveId, "save_added", Map.of());
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Save> listForUser(UUID userId, int page, int size) {
        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return saves.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(page, 0), bounded));
    }

    /**
     * The "Continue" rail: saves the user has moved past {@code saved} but not
     * finished. Empty rather than an error when nothing is in flight.
     */
    @Transactional(readOnly = true)
    public List<Save> listForUserByLifecycle(UUID userId, Collection<LifecycleStatus> statuses,
                                             int page, int size) {
        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return saves.findByUserIdAndLifecycleStatusInOrderByUpdatedAtDesc(
                userId, statuses, PageRequest.of(Math.max(page, 0), bounded));
    }

    /**
     * A Space's feed. Membership is the caller's to establish — every caller
     * reaches this through {@code SpaceController}, which does.
     */
    @Transactional(readOnly = true)
    public List<Save> listForSpace(UUID spaceId, int page, int size) {
        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return saves.findBySpaceIdOrderByCreatedAtDesc(spaceId, PageRequest.of(Math.max(page, 0), bounded));
    }

    /**
     * Own saves, plus anything in a Space the caller belongs to.
     *
     * <p>The Space fallback is the whole point of a shared Space — before it,
     * a member could see a save in the feed and get a 404 opening it. It is
     * checked second and only on a miss, so the common case stays one indexed
     * lookup.
     */
    @Transactional(readOnly = true)
    public Save getForUser(UUID userId, UUID saveId) {
        Optional<Save> own = saves.findByIdAndUserId(saveId, userId);
        if (own.isPresent()) {
            return own.get();
        }
        Save shared = saves.findById(saveId)
                .filter(save -> save.getSpaceId() != null)
                .filter(save -> spaces.roleOf(userId, save.getSpaceId()).isPresent())
                .orElseThrow(() -> new NotFoundException("Save not found"));
        return shared;
    }

    /**
     * Loads a specific set of the caller's saves, preserving the order asked for.
     *
     * <p>Ownership is in the query rather than filtered afterwards, for the same
     * reason as every other read here: the service layer is the access-control
     * boundary, so it must not fetch a row it is not allowed to return. There is
     * deliberately no Space fallback — the only caller is the group tree, which
     * is built from the user's own saves, so a shared save reached this way
     * would be one the tree never put there.
     *
     * <p>Ordering is restored client-side of the query because {@code IN} does
     * not preserve it, and the groups it feeds are ordered deliberately.
     */
    @Transactional(readOnly = true)
    public List<SaveResponse> getAllByIds(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Save> found = saves.findByIdInAndUserId(ids, userId).stream()
                .collect(Collectors.toMap(Save::getId, save -> save));
        return ids.stream().map(found::get).filter(Objects::nonNull).map(SaveResponse::from).toList();
    }

    /**
     * Moves a save along {@code saved → planned → started → completed}.
     *
     * <p>Deliberately <em>not</em> a state machine. Un-completing something you
     * decided you had not finished after all, or dropping a planned recipe back
     * to plain saved, are both ordinary things to want; rejecting them would buy
     * nothing but a 400 the user cannot act on. The enum is the only constraint,
     * and it is also the database's.
     */
    @Transactional
    public Save setLifecycle(UUID userId, UUID saveId, LifecycleStatus status) {
        Save save = saves.findByIdAndUserId(saveId, userId)
                .orElseThrow(() -> new NotFoundException("Save not found"));
        save.setLifecycleStatus(status);

        // Only `completed` reaches the Space feed. "Planned" and "started" are
        // intentions, and a feed full of other people's intentions is the noise
        // the sparse-events rule exists to keep out — but "someone actually
        // cooked this" is exactly what a shared Space is for.
        if (status == LifecycleStatus.COMPLETED) {
            spaces.recordActivity(save.getSpaceId(), userId, saveId, "completed", Map.of());
        }

        log.info("Save {} lifecycle → {}", saveId, status.db());
        return save;
    }

    /**
     * Toggles favorite/archived from the Library's swipe actions. Either field
     * may be {@code null} to leave it untouched, so a single favorite swipe
     * doesn't have to know or resend the current archived state.
     */
    @Transactional
    public Save setFlags(UUID userId, UUID saveId, Boolean favorite, Boolean archived) {
        Save save = saves.findByIdAndUserId(saveId, userId)
                .orElseThrow(() -> new NotFoundException("Save not found"));
        if (favorite != null) {
            save.setFavorite(favorite);
        }
        if (archived != null) {
            save.setArchived(archived);
        }
        return save;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
