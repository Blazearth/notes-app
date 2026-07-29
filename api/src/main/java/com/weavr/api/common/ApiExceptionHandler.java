package com.weavr.api.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details. Messages are safe to show a user; internals go to
 * the log, not the response.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getAllErrors().stream()
                .map(err -> err.getDefaultMessage() == null ? "invalid" : err.getDefaultMessage())
                .distinct()
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Covers {@code ResponseStatusException}, which the catch-all below would
     * otherwise flatten into a 500 — the @CurrentUser resolver raises 401 this
     * way.
     */
    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail handleErrorResponse(ErrorResponseException e) {
        return e.getBody();
    }

    /**
     * Let the security filter chain produce its own 401/403 rather than
     * swallowing them into the catch-all.
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurityExceptions(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }
}
