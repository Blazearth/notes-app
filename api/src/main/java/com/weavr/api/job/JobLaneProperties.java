package com.weavr.api.job;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * One worker lane: a set of job types, and how many of them may run at once.
 *
 * <p><b>A lane is a cost class, not a job type.</b> Before lanes existed every
 * job shared one semaphore, so a {@code classify_save} — a single HTTPS call
 * idling on a socket — queued behind a {@code process_save} that might be
 * running ffmpeg and twelve sequential tesseract processes for up to nine
 * minutes. That is head-of-line blocking, and it is the whole reason concurrent
 * saves degraded (see {@code docs/parallel-processing.md} §1).
 *
 * <p>The lanes are sized against <em>different</em> resources, which is why
 * their concurrencies are not interchangeable:
 *
 * <ul>
 *   <li><b>fast</b> is network-bound. Its ceiling is the database pool, not the
 *       CPU — the work is waiting on Gemini, TMDB or Supabase, so several at
 *       once cost almost nothing in memory.</li>
 *   <li><b>heavy</b> is CPU- and memory-bound. It stays at 1 on a 512 MB /
 *       0.1 CPU instance because parallel ffmpeg and tesseract OOM rather than
 *       queue — the original reasoning, still correct <em>for this class of
 *       work</em>.</li>
 * </ul>
 *
 * <p>Do not raise the heavy lane to "use up" spare fast-lane capacity. Database
 * connections and RAM are not fungible.
 *
 * @param concurrency how many jobs of this lane run at once
 * @param types       job types claimed by this lane. A type must appear in
 *                    exactly one lane; anything a handler registers but no lane
 *                    claims is swept into the fallback lane, loudly
 * @param fallback    whether unclaimed job types land here. Exactly one lane
 *                    should set this, and it should be the most conservative
 *                    one — an unrecognised job's cost is unknown, and assuming
 *                    it is expensive is the safe error
 */
public record JobLaneProperties(

        @Min(1) @Max(8) int concurrency,

        List<String> types,

        boolean fallback
) {

    public JobLaneProperties {
        if (concurrency < 1) concurrency = 1;
        types = types == null ? List.of() : List.copyOf(types);
    }
}
