package com.weavr.api.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The backbone of the pipeline: claims jobs and dispatches them to handlers.
 * Everything downstream — the extraction cascade, the Gemini call, enrichment,
 * embedding — is a {@link JobHandler}, not a change to this class.
 *
 * <p>Runs a single poller thread feeding a small fixed pool. The pool is
 * deliberately platform threads rather than virtual ones even though the
 * application enables virtual threads globally: the work is ffmpeg, yt-dlp and
 * OCR, which are CPU- and memory-bound external processes. Virtual threads would
 * happily start fifty of them and OOM a free-tier instance — the bound *is* the
 * feature.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final JobProperties properties;
    private final Map<String, JobHandler> handlers;

    private volatile boolean running;
    private ExecutorService workers;
    private Thread poller;
    private Semaphore slots;
    private Instant nextReap = Instant.EPOCH;

    JobRunner(JobStore store, JobProperties properties, List<JobHandler> handlerList) {
        this.store = store;
        this.properties = properties;
        this.handlers = handlerList.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
    }

    /**
     * Started after the context is ready rather than in a constructor, so the
     * first claim cannot race Flyway or the connection pool.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Job runner disabled (weavr.jobs.enabled=false)");
            return;
        }
        if (handlers.isEmpty()) {
            log.warn("Job runner enabled but no handlers are registered — every job will fail");
        }

        int concurrency = properties.concurrency();
        slots = new Semaphore(concurrency);
        workers = Executors.newFixedThreadPool(concurrency, namedFactory());
        running = true;

        poller = new Thread(this::pollLoop, "weavr-job-poller");
        poller.setDaemon(true);
        poller.start();

        log.info("Job runner started: concurrency={} claimedBy={} types={}",
                concurrency, properties.claimedBy(), handlers.keySet());
    }

    private void pollLoop() {
        while (running) {
            try {
                reapIfDue();

                // Block until a worker is free, so we never claim a job we
                // cannot start — a claimed job is invisible to other instances.
                slots.acquire();

                Optional<JobRecord> claimed;
                try {
                    claimed = store.claim();
                } catch (RuntimeException e) {
                    slots.release();
                    log.error("Claim failed — backing off", e);
                    sleep(properties.pollInterval());
                    continue;
                }

                if (claimed.isEmpty()) {
                    slots.release();
                    sleep(properties.pollInterval());
                    continue;
                }

                JobRecord job = claimed.get();
                workers.execute(() -> {
                    try {
                        run(job);
                    } finally {
                        slots.release();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The poller must never die: it is the only thing feeding the
                // pool, and a dead poller is a silently stalled pipeline.
                log.error("Unexpected error in job poller", e);
                sleep(properties.pollInterval());
            }
        }
    }

    private void reapIfDue() {
        Instant now = Instant.now();
        if (now.isBefore(nextReap)) {
            return;
        }
        // Twice per stale window, so a dead worker's jobs come back promptly
        // without hammering the database.
        nextReap = now.plus(properties.staleAfter().dividedBy(2));
        try {
            store.requeueStale();
        } catch (RuntimeException e) {
            log.warn("Stale-claim sweep failed", e);
        }
    }

    /** Visible for testing — runs one job and records its outcome. */
    void run(JobRecord job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            log.error("No handler for job type '{}' (job {}) — failing it", job.type(), job.id());
            store.fail(job.id(), "No handler registered for type " + job.type());
            return;
        }

        long startedAt = System.nanoTime();
        try {
            handler.handle(job);
            store.succeed(job.id());
            log.info("Job {} ({}) succeeded in {}ms", job.id(), job.type(), millisSince(startedAt));

        } catch (RetryAfterException e) {
            // Not a failure. Costs no attempt — see RetryAfterException.
            store.retryAfter(job.id(), e.delay(), e.getMessage());
            log.info("Job {} ({}) deferred {} — {}", job.id(), job.type(), e.delay(), e.getMessage());

        } catch (PermanentJobException e) {
            store.fail(job.id(), e.getMessage());
            safeOnFailure(handler, job, e.errorCode(), e.userMessage());
            log.warn("Job {} ({}) failed permanently: {}", job.id(), job.type(), e.getMessage());

        } catch (Exception e) {
            // Anything unrecognised is treated as retryable. Transient infra
            // failures are far commoner than deterministic ones, and
            // max_attempts bounds the damage when the guess is wrong.
            int attemptsAfter = job.attempts() + 1;
            if (attemptsAfter >= job.maxAttempts()) {
                store.fail(job.id(), describe(e));
                safeOnFailure(handler, job, "job_failed", "We couldn't process this save.");
                log.warn("Job {} ({}) failed after {} attempts", job.id(), job.type(), attemptsAfter, e);
            } else {
                store.retry(job.id(), job.attempts(), describe(e));
                log.warn("Job {} ({}) attempt {}/{} failed, retrying in {}",
                        job.id(), job.type(), attemptsAfter, job.maxAttempts(),
                        store.backoffFor(attemptsAfter), e);
            }
        }
    }

    /**
     * A handler that throws while reporting a failure must not mask the failure
     * itself — the job is already marked failed by this point.
     */
    private void safeOnFailure(JobHandler handler, JobRecord job, String errorCode, String userMessage) {
        try {
            handler.onPermanentFailure(job, errorCode, userMessage);
        } catch (RuntimeException e) {
            log.error("onPermanentFailure threw for job {} ({})", job.id(), job.type(), e);
        }
    }

    @PreDestroy
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Job runner stopping — waiting for in-flight jobs");

        if (poller != null) {
            poller.interrupt();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                // In-flight jobs get a grace period. Whatever is still running
                // when it expires keeps its `running` row, and the stale-claim
                // sweep hands it to the next instance — which is why handlers
                // have to be safe to run twice.
                if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("In-flight jobs did not finish in 30s; they will be requeued as stale");
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
        }
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "weavr-job-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
