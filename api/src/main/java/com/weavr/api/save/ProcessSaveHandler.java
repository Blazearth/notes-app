package com.weavr.api.save;

import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for a new save.
 *
 * <p><b>The cascade is not here yet.</b> Today this claims the save, records that
 * the runner reached it, and stops — leaving {@code status = 'processing'}. That
 * is deliberate: the runner is the backbone and is worth shipping and watching on
 * its own, but nothing yet fetches captions, metadata or audio, so pretending a
 * save is `ready` would be a lie the whole UI would repeat.
 *
 * <p>Phase 2 fills in the ordered cascade — platform captions, then post
 * metadata, then ASR, then keyframes — each writing its own {@code save_stages}
 * row, and only then does the save advance.
 */
@Component
class ProcessSaveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessSaveHandler.class);

    static final String STAGE_ACCEPTED = "accepted";

    private final SaveRepository saves;
    private final SaveStageWriter stages;
    private final JdbcClient jdbc;

    ProcessSaveHandler(SaveRepository saves, SaveStageWriter stages, JdbcClient jdbc) {
        this.saves = saves;
        this.stages = stages;
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

        // Safe to run twice: the stage write is an upsert, and nothing here
        // mutates the save. The stale-claim reaper can and will re-deliver.
        stages.record(saveId, STAGE_ACCEPTED, Map.of(
                "sourceType", save.getSourceType().db(),
                "hasUrl", save.getSourceUrl() != null,
                "attempt", job.attempts() + 1));

        log.info("Save {} reached the runner (type={}). Extraction cascade is not implemented yet — "
                + "leaving status=processing.", saveId, save.getSourceType().db());
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
