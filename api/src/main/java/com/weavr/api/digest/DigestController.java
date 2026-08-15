package com.weavr.api.digest;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.billing.UsageService;
import com.weavr.api.job.JobPriority;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/digest} — the current week's summary, generated on demand.
 *
 * <p>No scheduler generates these ahead of time. The first request for a week
 * that has no cached row enqueues generation and returns a pending signal,
 * same shape as {@code POST /v1/saves}: 202 while a Gemini request is in
 * flight, the real thing once the job lands. Nobody pays for a digest nobody
 * opens, and staggering falls out for free — digests only ever generate when
 * someone is actually looking at Home.
 *
 * <p>Checked here rather than left to the handler: a week with zero ready
 * saves would otherwise enqueue a job that permanently fails ({@code
 * nothing_saved}), and the idempotency key on {@code (user, week)} means every
 * later {@code GET} that same week would find the key already spent and
 * enqueue nothing new — "pending" forever, for a week that will never produce
 * a digest. Same shape as {@link com.weavr.api.act.ShoppingListController}:
 * checked here for a clean response now, and the handler still owns the
 * authoritative check for two requests racing each other.
 */
@RestController
class DigestController {

    private final DigestService digests;
    private final JobQueue jobQueue;
    private final JdbcClient jdbc;

    DigestController(DigestService digests, JobQueue jobQueue, JdbcClient jdbc) {
        this.digests = digests;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
    }

    record DigestResponse(String summary, int saveCount, LocalDate weekStart, String status) {
        static DigestResponse ready(DigestService.Digest digest) {
            return new DigestResponse(digest.summary(), digest.saveCount(), digest.weekStart(), "ready");
        }

        static DigestResponse pending(LocalDate weekStart) {
            return new DigestResponse(null, 0, weekStart, "pending");
        }

        static DigestResponse empty(LocalDate weekStart) {
            return new DigestResponse(null, 0, weekStart, "empty");
        }
    }

    @GetMapping("/v1/digest")
    ResponseEntity<DigestResponse> get(@CurrentUser UUID userId) {
        LocalDate weekStart = UsageService.weekStart();

        Optional<DigestService.Digest> existing = digests.find(userId, weekStart);
        if (existing.isPresent()) {
            return ResponseEntity.ok(DigestResponse.ready(existing.get()));
        }

        if (!hasSavesThisWeek(userId, weekStart)) {
            return ResponseEntity.ok(DigestResponse.empty(weekStart));
        }

        // Keyed by (user, week): a second GET before the first job finishes
        // enqueues nothing new rather than a duplicate call.
        // BACKGROUND: the caller gets `202 pending` and reads the result minutes
        // to hours later, so a digest must never sit ahead of a save someone
        // just shared. This is the inversion the priority column existed to fix
        // and never did — see JobPriority.
        jobQueue.enqueueForUser(
                JobType.GENERATE_DIGEST,
                Map.of("userId", userId.toString(), "weekStart", weekStart.toString()),
                JobType.GENERATE_DIGEST + ":" + userId + ":" + weekStart,
                userId,
                JobPriority.BACKGROUND);
        return ResponseEntity.accepted().body(DigestResponse.pending(weekStart));
    }

    private boolean hasSavesThisWeek(UUID userId, LocalDate weekStart) {
        Boolean exists = jdbc.sql("""
                        select exists(
                            select 1 from saves
                            where user_id = ? and status = 'ready'
                              and created_at >= ? and created_at < ?
                        )
                        """)
                .param(userId)
                .param(weekStart)
                .param(weekStart.plusDays(7))
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }
}
