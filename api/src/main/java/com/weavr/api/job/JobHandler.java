package com.weavr.api.job;

/**
 * One job type's work.
 *
 * <p>Implementations signal outcomes by exception, not return value:
 * <ul>
 *   <li>return normally — succeeded</li>
 *   <li>{@link RetryAfterException} — refused for now; retry without spending an attempt</li>
 *   <li>{@link PermanentJobException} — will never work; fail now, with a message for the user</li>
 *   <li>{@link RetryableJobException} or anything else — spend an attempt and back off</li>
 * </ul>
 *
 * <p>A handler runs outside any transaction. It must be safe to run twice: a
 * process that dies mid-job leaves the row claimed, and the stale-claim reaper
 * will hand the same job to someone else.
 */
public interface JobHandler {

    /** Matches {@code jobs.type}. See {@link JobType}. */
    String type();

    void handle(JobRecord job);

    /**
     * Called once when a job is terminally failed — out of attempts, or a
     * {@link PermanentJobException}.
     *
     * <p>The runner deliberately knows nothing about saves, so this is where a
     * handler propagates the failure to whatever the user actually sees. Without
     * it a failed pipeline leaves the save showing "Processing" forever, and with
     * silent capture nobody is watching to notice.
     */
    default void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
    }
}
