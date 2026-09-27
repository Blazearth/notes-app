package com.weavr.api.save;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.analytics.AnalyticsEvents;
import com.weavr.api.analytics.AnalyticsService;
import com.weavr.api.billing.UsageService;
import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.gemini.GeminiClient;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.gemini.GeminiResponse;
import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline stage 2: call Gemini to classify and extract structured data from
 * the text that {@link ProcessSaveHandler} landed in {@code save_stages}.
 *
 * <p>The chain is:
 * <pre>
 *   process_save  → save_stages(stage='extracted')
 *   classify_save → Gemini call → save.status = ready
 * </pre>
 *
 * <p>Idempotency: if {@code save_stages} already holds {@code stage='classified'}
 * from a previous attempt, the Gemini call is skipped — a retry does not
 * re-spend a request.
 *
 * <p>Model routing:
 * <ol>
 *   <li>Primary model (Flash Lite, 500 RPD) for every save.</li>
 *   <li>If primary confidence &lt; threshold, retry with fallback (2.5 Flash, 20 RPD).</li>
 *   <li>If both budgets are exhausted, {@link GeminiBudgetService} throws
 *       {@link com.weavr.api.job.RetryAfterException} — no attempt spent,
 *       save parks until tomorrow's reset.</li>
 * </ol>
 */
@Component
class ClassifySaveHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ClassifySaveHandler.class);

    static final String STAGE_CLASSIFIED = "classified";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** Matches the Supabase bucket's own 10 MB upload limit, as in {@link ProcessSaveHandler}. */
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final SaveRepository saves;
    private final SaveStageWriter stages;
    private final GeminiClient geminiClient;
    private final GeminiBudgetService budgetService;
    private final GeminiProperties geminiProps;
    private final JobQueue jobQueue;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final UsageService usage;
    private final com.weavr.api.pipeline.SafeUrlFetcher fetcher;
    private final AnalyticsService analytics;

    ClassifySaveHandler(SaveRepository saves, SaveStageWriter stages,
                        GeminiClient geminiClient, GeminiBudgetService budgetService,
                        GeminiProperties geminiProps, JobQueue jobQueue, JdbcClient jdbc,
                        ObjectMapper objectMapper, UsageService usage,
                        com.weavr.api.pipeline.SafeUrlFetcher fetcher, AnalyticsService analytics) {
        this.saves = saves;
        this.stages = stages;
        this.geminiClient = geminiClient;
        this.budgetService = budgetService;
        this.geminiProps = geminiProps;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.usage = usage;
        this.fetcher = fetcher;
        this.analytics = analytics;
    }

    @Override
    public String type() {
        return JobType.CLASSIFY_SAVE;
    }

    @Override
    public void handle(JobRecord job) {
        UUID saveId = job.uuidParam("saveId");
        Instant stageStart = Instant.now();

        Save save = saves.findById(saveId).orElseThrow(() ->
                new PermanentJobException("save_deleted", "This save no longer exists."));

        // Idempotency: if a previous attempt already classified this save,
        // don't call Gemini again. Just re-apply the result.
        Map<String, Object> existing = readStage(saveId, STAGE_CLASSIFIED);
        if (existing != null) {
            log.info("Save {} already classified (idempotent retry), re-applying result.", saveId);
            applyResult(saveId, existing);
            return;
        }

        // Read the image stage BEFORE the text stage. For a screenshot save the
        // bytes *are* the input, and extractText() would throw looking for the
        // text stage that the image path never writes (it records
        // STAGE_IMAGE_READY instead) — which made the vision branch below
        // unreachable and failed every screenshot with "no_extraction".
        Map<String, Object> imageStage = save.getSourceType() == SourceType.IMAGE
                ? readStage(saveId, ProcessSaveHandler.STAGE_IMAGE_READY)
                : null;
        byte[] imageBytes = imageStage == null ? null : resolveImageBytes(saveId, save, imageStage);
        boolean hasImage = imageBytes != null && imageBytes.length > 0;

        // Read the extracted text from stage 1 — text path only.
        String text = hasImage ? null : extractText(saveId, save);

        // The free-tier cap, checked before the budget rather than after: both
        // guard the same shared pool, but this one is about *whose* share it is.
        // Permanent, not retryable — the allowance will not come back within the
        // job's retry window, and re-queueing a save until next month is worse
        // than telling the user now.
        UsageService.Allowance allowance = usage.checkSaves(save.getUserId());
        if (!allowance.allowed()) {
            throw new PermanentJobException("quota_exceeded",
                    "You've used all %d free saves this month. Upgrade for unlimited saves."
                            .formatted(allowance.limit()));
        }

        // Acquire budget — throws RetryAfterException if daily pool is exhausted.
        BudgetApproved budget = budgetService.acquire();

        // Call Gemini — vision path for image saves (the raw bytes are in
        // save_stages, so Gemini sees full visual context: UI, overlay text,
        // layout, rather than a lossy OCR intermediate), text path otherwise.
        GeminiResponse response;
        String mimeType = hasImage
                ? imageStage.getOrDefault("mime_type", "image/jpeg").toString()
                : null;
        if (hasImage) {
            log.info("Save {} routing to Gemini image classify ({} bytes)", saveId, imageBytes.length);
            response = geminiClient.classifyImage(saveId, imageBytes, mimeType, budget);
        } else {
            // Text path: URL saves, TEXT saves, and legacy IMAGE/OCR saves.
            response = geminiClient.classify(saveId, text, budget);
        }

        // If primary confidence is below threshold and we can afford fallback, retry.
        boolean escalated = false;
        if (response.confidence() < geminiProps.confidenceThreshold()
                && !budget.model().equals(geminiProps.fallbackModel())) {
            log.info("Save {} low confidence ({}) on primary, trying fallback model",
                    saveId, response.confidence());
            try {
                BudgetApproved fallbackBudget = budgetService.acquireFor(
                        geminiProps.fallbackModel(), geminiProps.fallbackRpd());
                // Reuses the bytes already in hand — the old code decoded the
                // base64 a second time here, which on the storage path would
                // have meant a second network fetch for no reason.
                GeminiResponse fallback = hasImage
                        ? geminiClient.classifyImage(saveId, imageBytes, mimeType, fallbackBudget)
                        : geminiClient.classify(saveId, text, fallbackBudget);
                // Use fallback result if it's more confident.
                if (fallback.confidence() >= response.confidence()) {
                    response = fallback;
                    escalated = true;
                }
            } catch (Exception e) {
                // Fallback budget exhausted or failed — use the primary result anyway.
                log.warn("Fallback model unavailable for save {}, using primary result: {}",
                        saveId, e.getMessage());
            }
        }

        // Write stage for idempotency on the next retry.
        Map<String, Object> stagePayload = new HashMap<>(response.structuredData());
        stagePayload.put("knowledgeType", response.knowledgeType());
        stagePayload.put("confidence", response.confidence());
        stagePayload.put("modelUsed", response.model());
        stages.record(saveId, STAGE_CLASSIFIED, stagePayload);

        // Apply to the save.
        applyResult(saveId, stagePayload);

        // Counted only on the path that actually spent a request. The
        // early-return above (stage already cached) skips it deliberately, so a
        // retried job never bills the user twice for one save.
        usage.countSave(save.getUserId());

        log.info("Save {} classified → type={} confidence={} model={}",
                saveId, response.knowledgeType(), response.confidence(), response.model());

        emitExtractionAnalytics(save, response, escalated, stageStart);

        // The save is already `ready` at this point. Enrichment and embedding
        // are enhancements on top of that, so they get their own jobs rather
        // than holding a complete save behind two more network calls.
        //
        // Enrichment first, and it enqueues the embedding itself: the vector is
        // built from structured_data, so embedding before enrichment would
        // permanently omit the director and the address — the very terms a
        // semantic search wants most.
        enqueueEnrich(saveId, save.getUserId());
    }

    /**
     * Enqueued in its own transaction, for the same reason
     * {@link ProcessSaveHandler} does it: {@link JobQueue#enqueueForUser}
     * requires an active transaction, and the runner calls {@code handle()}
     * outside one.
     *
     * <p>Failure here is logged and swallowed. The save is already complete and
     * findable by full-text search; letting a queue hiccup fail the job would
     * re-run the whole classify path — and its Gemini request — to redo work
     * that succeeded.
     */
    @Transactional
    void enqueueEnrich(UUID saveId, UUID userId) {
        try {
            jobQueue.enqueueForUser(
                    JobType.ENRICH_SAVE,
                    Map.of("saveId", saveId.toString()),
                    JobType.ENRICH_SAVE + ":" + saveId,
                    userId);
        } catch (RuntimeException e) {
            log.warn("Could not enqueue enrichment for save {}: {}", saveId, e.toString());
        }
    }

    private String extractText(UUID saveId, Save save) {
        // For TEXT saves, use rawCaption directly. An IMAGE save reaching here
        // is a legacy on-device-OCR save (no uploaded bytes, so no image stage)
        // and its OCR text lives in the same place — without this it would look
        // for a URL extraction stage that was never written.
        if (save.getSourceType() == SourceType.TEXT || save.getSourceType() == SourceType.IMAGE) {
            String text = save.getRawCaption();
            if (text == null || text.isBlank()) {
                throw new PermanentJobException("no_text", "This save has no content to classify.");
            }
            return text;
        }

        // For URL saves, read what the extraction cascade produced.
        Map<String, Object> extractedStage = readStage(saveId, ProcessSaveHandler.STAGE_EXTRACTED);
        if (extractedStage == null) {
            throw new PermanentJobException("no_extraction",
                    "Text extraction hasn't run yet for this save.");
        }
        Object textObj = extractedStage.get("text");
        if (textObj == null || textObj.toString().isBlank()) {
            throw new PermanentJobException("empty_extraction",
                    "No text was extracted from this save.");
        }
        return textObj.toString();
    }

    @Transactional
    protected void applyResult(UUID saveId, Map<String, Object> stagePayload) {
        String knowledgeType = String.valueOf(stagePayload.get("knowledgeType"));
        Object confidenceObj = stagePayload.get("confidence");
        double confidence = confidenceObj instanceof Number n ? n.doubleValue() : 0.0;
        String modelUsed = String.valueOf(stagePayload.getOrDefault("modelUsed", "unknown"));

        // Build structured_data: everything except the meta fields.
        Map<String, Object> structuredData = new HashMap<>(stagePayload);
        structuredData.remove("knowledgeType");
        structuredData.remove("confidence");
        structuredData.remove("modelUsed");

        String structuredJson;
        try {
            structuredJson = objectMapper.writeValueAsString(structuredData);
        } catch (Exception e) {
            structuredJson = "{}";
        }

        // "unusable" means the pipeline ran successfully but the content had nothing
        // extractable (login wall, video-only short, empty transcript, etc.).
        // Mark it `ready` — the job is done; there just isn't useful knowledge.
        // Marking it `failed` was misleading: nothing went wrong server-side.
        String newStatus = "ready";
        String errorMessage = null;

        jdbc.sql("""
                        update saves
                        set status          = ?,
                            knowledge_type  = ?,
                            confidence      = ?,
                            structured_data = ?::jsonb,
                            model_used      = ?,
                            error_code      = null,
                            error_message   = ?,
                            updated_at      = now()
                        where id = ?
                        """)
                .param(newStatus)
                .param(knowledgeType)
                .param(confidence)
                .param(structuredJson)
                .param(modelUsed)
                .param(errorMessage)
                .param(saveId)
                .update();
    }

    /**
     * {@code extraction_completed} (pipeline quality, fires on every job) and
     * {@code save_ready} (activation, meaningful only with {@code is_first_save}
     * attached) — kept as two events per §E rather than one, so the activation
     * funnel never has to filter a generic pipeline event by a property that
     * doesn't belong on it.
     */
    private void emitExtractionAnalytics(Save save, GeminiResponse response, boolean escalated,
                                         Instant stageStart) {
        long latencyMs = Duration.between(stageStart, Instant.now()).toMillis();
        analytics.capture(save.getUserId(), AnalyticsEvents.EXTRACTION_COMPLETED, Map.of(
                "knowledge_type", response.knowledgeType(),
                "confidence", response.confidence(),
                "model_used", response.model(),
                "escalated", escalated,
                "latency_ms", latencyMs));

        analytics.capture(save.getUserId(), AnalyticsEvents.SAVE_READY, Map.of(
                "knowledge_type", response.knowledgeType(),
                "is_first_save", isFirstReadySave(save.getUserId(), save.getId()),
                "time_to_ready_ms", Duration.between(save.getCreatedAt(), Instant.now()).toMillis()));
    }

    /** Approximate by design: a race with a second concurrent first save is not worth guarding against for an analytics property. */
    private boolean isFirstReadySave(UUID userId, UUID saveId) {
        Boolean anyOtherReady = jdbc.sql("""
                        select exists(
                            select 1 from saves where user_id = ? and status = 'ready' and id <> ?
                        )
                        """)
                .param(userId)
                .param(saveId)
                .query(Boolean.class)
                .single();
        return !Boolean.TRUE.equals(anyOtherReady);
    }

    /**
     * The image bytes for a screenshot save, from whichever shape its stage row
     * uses.
     *
     * <p><b>Both shapes are permanent.</b> Saves processed before 2026-08-15
     * carry the bytes inline as {@code image_b64}; newer ones carry only
     * {@code image_url} and the bytes are re-fetched from Supabase Storage. This
     * codebase has no reprocess path, so the old shape never ages out — the same
     * permanent-dual-shape situation as flat-string recipe ingredients.
     *
     * <p>Returns {@code null} rather than throwing when neither shape yields
     * anything: an IMAGE save whose bytes are unreachable can still fall through
     * to the text path, which is what legacy on-device-OCR saves rely on.
     */
    private byte[] resolveImageBytes(UUID saveId, Save save, Map<String, Object> imageStage) {
        // Legacy shape first — if the bytes are already here, never pay for a
        // network round trip to fetch what we are holding.
        if (imageStage.get("image_b64") instanceof String b64 && !b64.isBlank()) {
            try {
                return java.util.Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                log.warn("Save {} has an unreadable inline image payload: {}", saveId, e.toString());
            }
        }

        Object stagedUrl = imageStage.get("image_url");
        String url = stagedUrl instanceof String s && !s.isBlank()
                ? s
                : save.getSourceUrl() != null ? save.getSourceUrl() : save.getMediaStoragePath();
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            byte[] bytes = fetcher.fetch(url, MAX_IMAGE_BYTES);
            return bytes.length == 0 ? null : bytes;
        } catch (RuntimeException e) {
            // Retryable by omission: throwing here would be reasonable too, but
            // returning null lets an IMAGE save with usable raw_caption still
            // classify from text rather than failing outright.
            log.warn("Save {} could not re-fetch its image from {}: {}", saveId, url, e.toString());
            return null;
        }
    }

    /**
     * Reads a stage payload from {@code save_stages}. Returns {@code null} if
     * the stage hasn't been written yet.
     */
    private Map<String, Object> readStage(UUID saveId, String stage) {
        return jdbc.sql("""
                        select payload::text from save_stages
                        where save_id = ? and stage = ?
                        """)
                .param(saveId)
                .param(stage)
                .query((rs, row) -> {
                    try {
                        return objectMapper.<Map<String, Object>>readValue(
                                rs.getString("payload"), MAP_TYPE);
                    } catch (Exception e) {
                        return Map.<String, Object>of();
                    }
                })
                .optional()
                .orElse(null);
    }

    /**
     * Marks the save as failed when all retries are exhausted.
     */
    @Override
    @Transactional
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        UUID saveId;
        try {
            saveId = job.uuidParam("saveId");
        } catch (PermanentJobException e) {
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

        saves.findById(saveId).ifPresent(save -> analytics.capture(save.getUserId(),
                AnalyticsEvents.EXTRACTION_FAILED,
                Map.of("stage", "classify", "error_type", errorCode,
                        "source_type", save.getSourceType().name())));
    }
}
