package com.weavr.api.pipeline.health;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where every provider attempt goes: one structured log line, plus a small
 * in-memory window of recent attempts.
 *
 * <p>A log line, not a table, on purpose — the smallest thing that makes
 * failure categories greppable on Render today without a migration. If the
 * canary shows the numbers are worth keeping, persisting them is additive.
 *
 * <p>Never throws. Recording is observation; it must not be able to fail a save.
 */
@Component
public class ExtractionAttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExtractionAttemptRecorder.class);

    private static final int RECENT_CAPACITY = 200;

    private final Deque<ExtractionAttempt> recent = new ArrayDeque<>();

    public void record(ExtractionAttempt attempt) {
        try {
            // Fixed key=value shape so `grep extraction_attempt | grep category=BOT_CHECK` works on raw logs.
            log.info("extraction_attempt context={} platform={} provider={} success={} category={} "
                            + "latency_ms={} http_status={} ytdlp={} detail={} at={}",
                    attempt.context(), attempt.platform(), attempt.provider(), attempt.success(),
                    attempt.category(), attempt.latencyMs(), attempt.httpStatus(), attempt.ytDlpVersion(),
                    attempt.detail(), attempt.timestamp());
            synchronized (recent) {
                if (recent.size() == RECENT_CAPACITY) {
                    recent.removeFirst();
                }
                recent.addLast(attempt);
            }
        } catch (RuntimeException e) {
            log.debug("Could not record extraction attempt: {}", e.getMessage());
        }
    }

    /** Oldest first. */
    public List<ExtractionAttempt> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
