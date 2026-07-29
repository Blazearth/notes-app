package com.weavr.api.job;

/**
 * Job types are plain strings in the database so that adding one is a data
 * change. These constants name the ones the pipeline dispatches on.
 */
public final class JobType {

    /** Entry point for a new save: runs the text-extraction cascade onward. */
    public static final String PROCESS_SAVE = "process_save";

    private JobType() {
    }
}
