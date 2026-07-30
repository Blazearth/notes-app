package com.weavr.api.job;

import java.time.Duration;

/**
 * The work was refused for a reason that has nothing to do with this job being
 * wrong — a rate limit, or the daily Gemini request budget being spent.
 *
 * <p>Reschedules at {@code now() + delay} and deliberately <strong>does
 * not</strong> increment {@code attempts}. That distinction is the whole point:
 * without it, a day of quota rejections would burn the retry budget of every
 * queued save and fail work that was never broken.
 */
public class RetryAfterException extends RuntimeException {

    private final Duration delay;

    public RetryAfterException(String message, Duration delay) {
        super(message);
        this.delay = delay;
    }

    public Duration delay() {
        return delay;
    }
}
