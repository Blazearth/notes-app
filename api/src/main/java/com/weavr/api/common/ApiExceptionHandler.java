package com.weavr.api.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail handleForbidden(ForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail handleBadRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Two requests carrying one {@code Idempotency-Key} are racing, so the
     * answer is not knowable yet.
     *
     * <p>409 rather than a 4xx the client would treat as terminal: this resolves
     * by asking again, and the app's outbox retries a 409 (it classifies
     * anything outside its known 4xx set as {@code server}, which backs off and
     * retries) where it would surface a 400 to the user.
     */
    @ExceptionHandler(IdempotencyService.ConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyService.ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * A cap the user can escape by upgrading. 402 rather than 403 so the client
     * can key the paywall off the status alone; {@code entitlement} and
     * {@code limit} ride along for the copy.
     */
    @ExceptionHandler(QuotaExceededException.class)
    ProblemDetail handleQuota(QuotaExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        problem.setProperty("quota", e.quota());
        problem.setProperty("limit", e.limit());
        problem.setProperty("used", e.used());
        return problem;
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
     * A body Jackson cannot read is the caller's mistake, not ours.
     *
     * <p>Without this the catch-all below turns it into a 500, which is
     * actively misleading: every {@link DbEnum} rejects an unknown value by
     * throwing from its {@code @JsonCreator}, so a typo'd {@code sourceType} or
     * {@code lifecycleStatus} looked like a server fault. The message is the
     * exception's own most-specific cause, which for that case names the bad
     * value and the field.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String detail = cause instanceof IllegalArgumentException && cause.getMessage() != null
                ? cause.getMessage()
                : "Request body could not be read";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
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
