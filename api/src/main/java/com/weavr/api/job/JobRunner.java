package com.weavr.api.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <p>Runs <b>one poller thread and one bounded pool per lane</b> (see
 * {@link JobLaneProperties}). A lane is a cost class: the fast lane is
 * network-bound work several slots wide, the heavy lane is ffmpeg/yt-dlp/OCR
 * pinned at one. Before lanes existed a single pool served both, so a
 * three-second Gemini call could sit behind a nine-minute OCR job — the actual
 * cause of degradation under concurrent saves
 * ({@code docs/parallel-processing.md} §1).
 *
 * <p>The pools are deliberately platform threads rather than virtual ones even
 * though the application enables virtual threads globally: the heavy lane's work
 * is CPU- and memory-bound external processes, and virtual threads would happily
 * start fifty of them and OOM a free-tier instance. The bound *is* the feature.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final JobProperties properties;
    private final Map<String, JobHandler> handlers;

    private volatile boolean running;
    private final List<Lane> lanes = new ArrayList<>();
    /**
     * Shared across lanes on purpose: the sweep is global, and running it once
     * per lane would multiply an identical write by the number of lanes.
     */
    private final AtomicReference<Instant> nextReap = new AtomicReference<>(Instant.EPOCH);

    JobRunner(JobStore store, JobProperties properties, List<JobHandler> handlerList) {
        this.store = store;
        this.properties = properties;
        this.handlers = handlerList.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
    }

    /** One lane's poller, pool and slot budget. */
    private final class Lane {
        private final String name;
        private final List<String> types;
        private final Semaphore slots;
        private final ExecutorService workers;
        private Thread poller;

        Lane(String name, List<String> types, int concurrency) {
            this.name = name;
            this.types = types;
            this.slots = new Semaphore(concurrency);
            this.workers = Executors.newFixedThreadPool(concurrency, namedFactory(name));
        }

        void start() {
            poller = new Thread(this::pollLoop, "weavr-job-poller-" + name);
            poller.setDaemon(true);
            poller.start();
        }

        private void pollLoop() {
            while (running) {
                try {
                    reapIfDue();

                    // Block until a worker is free, so we never claim a job we
                    // cannot start — a claimed job is invisible to other
                    // instances until the stale reaper releases it.
                    slots.acquire();

                    Optional<JobRecord> claimed;
                    try {
                        claimed = store.claim(types);
                    } catch (RuntimeException e) {
                        slots.release();
                        log.error("Claim failed in lane {} — backing off", name, e);
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
                    // A poller must never die: it is the only thing feeding its
                    // pool, and a dead poller is a silently stalled lane.
                    log.error("Unexpected error in job poller for lane {}", name, e);
                    sleep(properties.pollInterval());
                }
            }
        }

        void stop() {
            if (poller != null) {
                poller.interrupt();
            }
            workers.shutdown();
        }

        void awaitTermination() {
            try {
                // In-flight jobs get a grace period. Whatever is still running
                // when it expires keeps its `running` row, and the stale-claim
                // sweep hands it to the next instance — which is why handlers
                // have to be safe to run twice.
                if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Lane {}: in-flight jobs did not finish in 30s; they will be requeued as stale", name);
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
        }
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

        running = true;
        for (Map.Entry<String, JobLaneProperties> entry : assignLanes().entrySet()) {
            JobLaneProperties config = entry.getValue();
            Lane lane = new Lane(entry.getKey(), config.types(), config.concurrency());
            lanes.add(lane);
            lane.start();
            log.info("Job lane '{}' started: concurrency={} types={}",
                    entry.getKey(), config.concurrency(),
                    config.types().isEmpty() ? "<all>" : config.types());
        }

        log.info("Job runner started: {} lane(s) claimedBy={} handlers={}",
                lanes.size(), properties.claimedBy(), handlers.keySet());
    }

    /**
     * Resolves configured lanes against the handlers actually registered.
     *
     * <p>The failure this exists to prevent is silent and total: a job type with
     * a handler but no lane is never claimed by anybody, so those saves sit
     * {@code queued} forever with nothing in the logs. Rather than let that
     * happen, unclaimed types are swept into the fallback lane and named in a
     * warning.
     */
    Map<String, JobLaneProperties> assignLanes() {
        Map<String, JobLaneProperties> configured = properties.resolvedLanes();

        Set<String> claimed = configured.values().stream()
                .flatMap(lane -> lane.types().stream())
                .collect(Collectors.toSet());

        // A lane with no declared types already claims everything, so nothing
        // can be orphaned in that shape.
        boolean someLaneTakesEverything = configured.values().stream().anyMatch(l -> l.types().isEmpty());
        List<String> orphaned = someLaneTakesEverything ? List.of() : handlers.keySet().stream()
                .filter(type -> !claimed.contains(type))
                .sorted()
                .toList();

        if (orphaned.isEmpty()) {
            return configured;
        }

        String fallbackName = configured.entrySet().stream()
                .filter(e -> e.getValue().fallback())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> configured.keySet().iterator().next());

        log.warn("Job type(s) {} are not assigned to any lane — adding them to the '{}' lane. "
                        + "Left unassigned they would never be claimed at all.",
                orphaned, fallbackName);

        Map<String, JobLaneProperties> resolved = new LinkedHashMap<>(configured);
        JobLaneProperties fallback = resolved.get(fallbackName);
        List<String> merged = new ArrayList<>(fallback.types());
        merged.addAll(orphaned);
        resolved.put(fallbackName, new JobLaneProperties(fallback.concurrency(), merged, fallback.fallback()));
        return resolved;
    }

    private void reapIfDue() {
        Instant now = Instant.now();
        Instant due = nextReap.get();
        if (now.isBefore(due)) {
            return;
        }
        // Twice per stale window, so a dead worker's jobs come back promptly
        // without hammering the database. CAS so two lanes' pollers arriving
        // together run the sweep once between them, not once each.
        if (!nextReap.compareAndSet(due, now.plus(properties.staleAfter().dividedBy(2)))) {
            return;
        }
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
        log.info("Job runner stopping — waiting for in-flight jobs across {} lane(s)", lanes.size());

        // Signal every lane before waiting on any of them, or the last lane's
        // grace period only starts once the first one's has elapsed.
        lanes.forEach(Lane::stop);
        lanes.forEach(Lane::awaitTermination);
    }

    private static ThreadFactory namedFactory(String lane) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "weavr-job-" + lane + "-" + counter.incrementAndGet());
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
