package com.weavr.api.job;

import java.util.Map;
import java.util.UUID;

/**
 * A claimed job. `payload` is the raw JSONB, decoded once by the store so
 * handlers do not each re-parse it.
 *
 * @param attempts     attempts *before* this one, so the first run sees 0
 * @param maxAttempts  from the row, not config — a job type can carry its own
 */
public record JobRecord(
        UUID id,
        String type,
        Map<String, Object> payload,
        int attempts,
        int maxAttempts,
        String groupId
) {

    /** Convenience for the common single-id payload. */
    public UUID uuidParam(String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new PermanentJobException("bad_payload", "This save is missing information we need.");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new PermanentJobException("bad_payload", "This save is missing information we need.", e);
        }
    }
}
