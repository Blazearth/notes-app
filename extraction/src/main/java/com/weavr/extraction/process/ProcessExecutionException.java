package com.weavr.extraction.process;

/**
 * The binary could not be run at all — missing from PATH, not executable, or the
 * thread was interrupted. Distinct from "it ran and told us no", which comes
 * back as a non-zero {@link ExternalProcess.Result}.
 */
public class ProcessExecutionException extends RuntimeException {

    public ProcessExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
