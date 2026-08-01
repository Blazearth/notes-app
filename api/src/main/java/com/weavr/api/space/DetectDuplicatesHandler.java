package com.weavr.api.space;

import java.util.UUID;

import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pipeline stage 5, and only for saves that landed in a shared Space.
 *
 * <p>Its own job rather than a tail call inside {@link
 * com.weavr.api.embed.EmbedSaveHandler} for one reason: an embedding that
 * succeeded must stay succeeded. Folding detection into that handler would mean
 * a failure here re-runs the embedding — a second call to a different API — to
 * redo work that was already done.
 *
 * <p>Costs no model request at all. The comparison is a pgvector query against
 * saves that are already embedded.
 */
@Component
class DetectDuplicatesHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DetectDuplicatesHandler.class);

    private final DuplicateDetector detector;

    DetectDuplicatesHandler(DuplicateDetector detector) {
        this.detector = detector;
    }

    @Override
    public String type() {
        return JobType.DETECT_DUPLICATES;
    }

    @Override
    public void handle(JobRecord job) {
        detector.detect(job.uuidParam("saveId"));
    }

    @Override
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        // A missed suggestion is a missed nicety. The saves are all intact and
        // the Space works exactly as it did before this feature existed.
        log.warn("Duplicate detection gave up for job {} ({})", job.id(), errorCode);
    }
}
