package com.weavr.api.job;

/**
 * Job types are plain strings in the database so that adding one is a data
 * change. These constants name the ones the pipeline dispatches on.
 */
public final class JobType {

    /** Entry point for a new save: runs the text-extraction cascade onward. */
    public static final String PROCESS_SAVE = "process_save";

    /** Stage 2: runs the Gemini classify-and-extract call on extracted text. */
    public static final String CLASSIFY_SAVE = "classify_save";

    private JobType() {
    }
}
