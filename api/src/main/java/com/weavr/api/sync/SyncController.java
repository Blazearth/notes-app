package com.weavr.api.sync;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /v1/sync} — the one read the local-first client makes on launch.
 *
 * <p>Nothing here decides anything: the window belongs to {@link SyncWindow} and
 * the scoping to {@link SyncService}, which is where authorization lives (every
 * query is keyed off {@code @CurrentUser}, never off an id in the request).
 */
@RestController
@RequestMapping("/v1/sync")
class SyncController {

    private final SyncService sync;

    SyncController(SyncService sync) {
        this.sync = sync;
    }

    /**
     * @param since the {@code until} from the client's last successful page, as
     *              ISO-8601. Omitted for a first sync. Parsed here rather than
     *              bound as an {@link Instant} so a malformed value is a
     *              deliberate 400 with a message, instead of depending on which
     *              converters happen to be registered.
     * @param limit rows per entity type; clamped to
     *              1..{@link SyncService#MAX_LIMIT}
     */
    @GetMapping
    SyncResponse sync(@CurrentUser UUID userId,
                      @RequestParam(required = false) String since,
                      @RequestParam(defaultValue = "" + SyncService.DEFAULT_LIMIT) int limit) {
        return sync.since(userId, parseSince(since), limit);
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            // A ResponseStatusException rather than an IllegalArgumentException:
            // `ApiExceptionHandler` already turns the former into a problem
            // detail and would flatten the latter into a 500, which for a
            // malformed query param is actively misleading.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "since must be an ISO-8601 instant, e.g. 2026-08-09T12:00:00.123Z");
        }
    }
}
