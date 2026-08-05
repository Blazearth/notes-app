package com.weavr.api.digest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.gemini.GeminiClient;
import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One Gemini call: turns a user's saves from one ISO week into the one or two
 * sentences the Home feed's digest tile shows.
 *
 * <p>A job rather than inline work in {@code GET /v1/digest}, for the same
 * reason every other model call is one — it spends a request from the same
 * budget saves and Acts draw from (CLAUDE.md: "saves are not the only
 * consumer"), so it has to be able to park when the daily pool is gone rather
 * than failing the request thread. {@link GeminiBudgetService} throws
 * {@code RetryAfterException} in that case, which reschedules without
 * spending an attempt.
 */
@Component
class GenerateDigestHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerateDigestHandler.class);

    private final JdbcClient jdbc;
    private final GeminiBudgetService budget;
    private final GeminiClient gemini;
    private final DigestService digests;

    GenerateDigestHandler(JdbcClient jdbc, GeminiBudgetService budget, GeminiClient gemini,
                          DigestService digests) {
        this.jdbc = jdbc;
        this.budget = budget;
        this.gemini = gemini;
        this.digests = digests;
    }

    @Override
    public String type() {
        return JobType.GENERATE_DIGEST;
    }

    private record SaveLine(String knowledgeType, String title) {
    }

    @Override
    public void handle(JobRecord job) {
        UUID userId = job.uuidParam("userId");
        LocalDate weekStart = LocalDate.parse((String) job.payload().get("weekStart"));

        List<SaveLine> saves = jdbc.sql("""
                        select knowledge_type,
                               coalesce(structured_data ->> 'title',
                                        structured_data ->> 'name') as title
                        from saves
                        where user_id = ?
                          and status = 'ready'
                          and created_at >= ?
                          and created_at < ?
                        order by created_at
                        """)
                .param(userId)
                .param(weekStart)
                .param(weekStart.plusDays(7))
                .query((rs, row) -> new SaveLine(
                        rs.getString("knowledge_type"),
                        rs.getString("title")))
                .list();

        // Nothing to summarise. Not a failure — a quiet week is the common
        // case, not an error — but there is nothing worth a Gemini request
        // for, and no row means the next GET correctly finds none rather than
        // caching an empty summary for the rest of the week.
        if (saves.isEmpty()) {
            throw new PermanentJobException("nothing_saved",
                    "No saves this week to summarise.");
        }

        List<String> lines = saves.stream()
                .map(s -> (s.knowledgeType() == null ? "other" : s.knowledgeType())
                        + ": " + (s.title() == null || s.title().isBlank() ? "untitled" : s.title()))
                .toList();

        BudgetApproved approved = budget.acquire();
        String summary = gemini.summarizeDigest(userId, lines, approved);

        if (summary.isBlank()) {
            throw new PermanentJobException("empty_summary",
                    "Weavr couldn't summarise this week's saves.");
        }

        digests.save(userId, weekStart, summary, saves.size());

        log.info("Digest generated user={} week={} saves={}", userId, weekStart, saves.size());
    }

    /**
     * No per-digest status row for the user to see, unlike a save — the tile
     * simply does not appear this week, which is visible on its own. Logging
     * is the whole failure surface, same as the shopping-list Act.
     */
    @Override
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        log.info("Digest generation for job {} did not produce a digest ({}): {}",
                job.id(), errorCode, userMessage);
    }
}
