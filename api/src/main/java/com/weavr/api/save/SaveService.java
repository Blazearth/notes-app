package com.weavr.api.save;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobType;
import com.weavr.api.profile.ProfileService;
import com.weavr.api.save.dto.CreateSaveRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    SaveService(SaveRepository saves, ProfileService profiles, JobQueue jobs) {
        this.saves = saves;
        this.profiles = profiles;
        this.jobs = jobs;
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

        log.info("Accepted save id={} type={} user={}", save.getId(), save.getSourceType(), userId);
        return save;
    }

    @Transactional(readOnly = true)
    public List<Save> listForUser(UUID userId, int page, int size) {
        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return saves.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(page, 0), bounded));
    }

    @Transactional(readOnly = true)
    public Save getForUser(UUID userId, UUID saveId) {
        return saves.findByIdAndUserId(saveId, userId)
                .orElseThrow(() -> new NotFoundException("Save not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
