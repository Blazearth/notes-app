package com.weavr.api.save;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.pipeline.ExtractionCascade;
import com.weavr.api.pipeline.SafeUrlFetcher;
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

    /** Matches the Supabase bucket's own 10 MB upload limit for images. */
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final SaveRepository saves;
    private final SaveStageWriter stages;
    private final ExtractionCascade cascade;
    private final JobQueue jobQueue;
    private final JdbcClient jdbc;
    private final SafeUrlFetcher fetcher;

    ProcessSaveHandler(SaveRepository saves, SaveStageWriter stages,
                       ExtractionCascade cascade, JobQueue jobQueue, JdbcClient jdbc,
                       SafeUrlFetcher fetcher) {
        this.saves = saves;
        this.stages = stages;
        this.cascade = cascade;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
        this.fetcher = fetcher;
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
            // TEXT saves already have their content in rawCaption.
            case TEXT -> requireText(save);
            // IMAGE: if mediaStoragePath is set, download the image bytes so
            // ClassifySaveHandler can send them directly to Gemini Vision.
            // Otherwise fall back to requireText() for backward-compat with
            // old on-device-OCR saves that pre-date the upload endpoint.
            case IMAGE -> save.getMediaStoragePath() != null
                    ? downloadAndStoreImage(saveId, save)
                    : requireText(save);
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

        ExtractionCascade.Extraction extraction =
                cascade.extractFromUrl(save.getSourceUrl(), saveId);

        // Persist thumbnail URL if the pipeline found one — used by the client
        // to show a real image instead of the placeholder hatch pattern.
        String thumbnailUrl = extraction.metadata() != null
                ? extraction.metadata().thumbnailUrl() : null;
        if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
            jdbc.sql("update saves set thumbnail_url = ? where id = ?")
                    .param(thumbnailUrl)
                    .param(saveId)
                    .update();
        }

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

    static final String STAGE_IMAGE_READY = "image_ready";

    /**
     * Downloads the image from Supabase Storage and writes its bytes (base64)
     * to {@code save_stages} so {@link ClassifySaveHandler} can send them
     * directly to Gemini Vision without a second download.
     *
     * <p>Returns a minimal placeholder text because the pipeline expects a
     * non-null, non-blank string here; {@code ClassifySaveHandler} ignores it
     * for IMAGE saves and reads the stage payload instead.
     */
    private String downloadAndStoreImage(UUID saveId, Save save) {
        String url = save.getSourceUrl() != null ? save.getSourceUrl() : save.getMediaStoragePath();
        if (url == null || url.isBlank()) {
            throw new PermanentJobException("no_image_url", "Image save has no storage URL.");
        }

        log.info("Downloading image for save={} from {}", saveId, url);

        byte[] imageBytes = fetcher.fetch(url, MAX_IMAGE_BYTES);

        if (imageBytes.length == 0) {
            throw new PermanentJobException("empty_image", "Downloaded image is empty.");
        }

        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        stages.record(saveId, STAGE_IMAGE_READY, Map.of(
                "image_b64", b64,
                "mime_type", "image/jpeg",
                "size_bytes", imageBytes.length
        ));

        log.info("Save {} image stored in stages: {} bytes", saveId, imageBytes.length);
        return "[image]";  // non-blank placeholder; classify_save uses stage data instead
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
