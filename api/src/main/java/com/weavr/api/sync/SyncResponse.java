package com.weavr.api.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.save.dto.SaveResponse;
import com.weavr.api.space.SpaceService;

/**
 * One page of {@code GET /v1/sync}.
 *
 * <p>Every list is "what changed in this window", never "everything" — except on
 * a first sync, where the window starts at the epoch and the two are the same
 * thing. That is deliberate: there is no separate bootstrap endpoint to keep
 * correct alongside this one.
 *
 * @param until   the cursor to send back as {@code since}. Comes from the
 *                <em>server's</em> clock via the rows it selected, never the
 *                client's — device clock skew is otherwise a silent data-loss
 *                bug, and this is the only reason the client never formats a
 *                timestamp of its own.
 * @param hasMore whether another page is waiting. A client loops until this is
 *                false, advancing its watermark after each applied page.
 */
public record SyncResponse(
        Instant until,
        boolean hasMore,

        /**
         * The caller's own saves. Deliberately <em>without</em> {@code itemStates}
         * — those travel as their own list below, and the client's store keeps
         * them in a separate table for exactly this reason (a save row written
         * from an endpoint that omits the field must not erase ticked
         * checkboxes). See {@code app/src/local/store.ts}.
         */
        List<SaveResponse> saves,

        /** Full {@code Space} payloads for any Space whose row changed. */
        List<SpaceService.Space> spaces,

        /**
         * The <em>whole</em> member list of any Space where any member row
         * changed, not the changed rows. One changed role is rare and a full
         * list is small, and it lets the client apply this with the
         * replace-per-space write it already has instead of a merge rule.
         */
        List<SpaceMembers> spaceMembers,

        List<ItemState> itemStates,
        List<EntityState> entityStates,
        List<Override> overrides,

        /** The only thing a delta cannot infer. See {@link TombstoneService}. */
        List<Deletion> deleted
) {

    public record SpaceMembers(UUID spaceId, List<SpaceService.Member> members) {
    }

    public record ItemState(UUID saveId, String itemPath, Map<String, Object> state) {
    }

    public record EntityState(String entityKey, Map<String, Object> state) {
    }

    public record Override(String overrideType, String subjectKey, Map<String, Object> payload) {
    }

    /** {@code type} is one of {@link TombstoneService}'s constants. */
    public record Deletion(String type, String id) {
    }
}
