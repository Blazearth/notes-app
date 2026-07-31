package com.weavr.api.save;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.pipeline.ExtractionCascade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for a new save: run the extraction cascade and park the result.
 *
 * <p><b>The save still does not reach {@code ready} here.</b> Extraction produces
 * text; turning that text into a knowledge type and structured fields is the
 * single Gemini call in Phase 3. Marking a save ready without it would be a lie
 * the whole UI repeats — so the text lands in {@code save_stages} and the save
 * waits.
 */
@Component
class ProcessSaveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessSaveHandler.class);

    static final String STAGE_EXTRACTED = "extracted";

    /**
     * Enough for any transcript worth reading, and a bound on what a runaway
     * caption file can push into a JSONB column.
     */
    private static final int MAX_STORED_TEXT = 200_000;

    private final SaveRepository saves;
    private final SaveStageWriter stages;
    private final ExtractionCascade cascade;
    private final JobQueue jobQueue;
    private final JdbcClient jdbc;

    ProcessSaveHandler(SaveRepository saves, SaveStageWriter stages,
                       ExtractionCascade cascade, JobQueue jobQueue, JdbcClient jdbc) {
        this.saves = saves;
        this.stages = stages;
        this.cascade = cascade;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return JobType.PROCESS_SAVE;
    }

    @Override
    public void handle(JobRecord job) {
        UUID saveId = job.uuidParam("saveId");

        Save save = saves.findById(saveId).orElseThrow(() -> new PermanentJobException(
                "save_deleted", "This save no longer exists."));

        String text = switch (save.getSourceType()) {
            case URL -> extractFromUrl(saveId, save);
            // Text the client already holds — nothing to fetch. A screenshot
            // carries on-device OCR output the same way a typed note carries
            // its caption (SourceType.IMAGE's own doc comment), so it takes
            // the identical path.
            case TEXT, IMAGE -> requireText(save);
            // Both need a Storage upload path that does not exist yet — the
            // client posts a link or text today, never a file. PDF-by-URL
            // already works: that is a URL save, and ExtractionCascade routes
            // a .pdf link straight to PdfExtractor without touching yt-dlp.
            case PDF, AUDIO -> throw new PermanentJobException(
                    "unsupported_source_type",
                    "Weavr can't process this kind of save yet.");
        };

        log.info("Save {} extracted {} chars, enqueuing classify_save", saveId, text.length());

        enqueueClassify(saveId, save.getUserId());
    }

    /**
     * Enqueues the classify_save job in its own transaction.
     *
     * <p>{@link JobQueue#enqueueForUser} requires an active transaction
     * ({@code propagation = MANDATORY}). The job runner calls
     * {@code handle()} outside any transaction, so we open one here for the
     * enqueue alone. The idempotency key makes duplicate enqueues a no-op.
     */
    @Transactional
    void enqueueClassify(UUID saveId, UUID userId) {
        jobQueue.enqueueForUser(
                JobType.CLASSIFY_SAVE,
                java.util.Map.of("saveId", saveId.toString()),
                "classify_save:" + saveId,
                userId);
    }

    private String extractFromUrl(UUID saveId, Save save) {
        if (save.getSourceUrl() == null || save.getSourceUrl().isBlank()) {
            throw new PermanentJobException("bad_payload", "That save has no link to open.");
        }

        ExtractionCascade.Extraction extraction = cascade.extractFromUrl(save.getSourceUrl());

        // Safe to run twice — the stage write is an upsert, and the stale-claim
        // reaper can re-deliver this job at any point.
        Map<String, Object> payload = new HashMap<>(ExtractionCascade.stagePayload(extraction));
        payload.put("text", truncate(extraction.text()));
        stages.record(saveId, STAGE_EXTRACTED, payload);

        return extraction.text();
    }

    private String requireText(Save save) {
        String text = save.getRawCaption();
        if (text == null || text.isBlank()) {
            throw new PermanentJobException("no_text_extracted", "That save is empty.");
        }
        return text;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_STORED_TEXT ? text : text.substring(0, MAX_STORED_TEXT);
    }

    /**
     * Surfaces the failure on the save itself. Without this the feed shows
     * "Processing" forever — and with silent capture nobody is watching to
     * notice something went wrong.
     */
    @Override
    @Transactional
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        UUID saveId;
        try {
            saveId = job.uuidParam("saveId");
        } catch (PermanentJobException e) {
            // The payload is what was broken; there is no save to update.
            return;
        }

        jdbc.sql("""
                        update saves
                        set status = 'failed', error_code = ?, error_message = ?, updated_at = now()
                        where id = ? and status <> 'ready'
                        """)
                .param(errorCode)
                .param(userMessage)
                .param(saveId)
                .update();
    }
}
