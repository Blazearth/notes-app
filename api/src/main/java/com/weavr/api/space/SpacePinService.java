package com.weavr.api.space;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.sync.TombstoneService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins — S4 of {@code docs/knowledge-spaces.md}, and the honest version of the
 * vision's "Current Program" row.
 *
 * <p><b>Why this is a table and not a model call.</b> The workout Space in the
 * vision shows a current program at the top of its Overview. The tempting
 * implementation merges several push-day saves into one routine, which is
 * exactly the synthesis {@code docs/knowledge-collections.md} rules out as
 * shape 3 — and its failure mode is somebody in a gym following a program no
 * human ever wrote. A pin is a member pointing at one save and saying "this
 * one". Identical row on the screen; entirely different claim behind it, and
 * only one of the two can be wrong in a way that matters.
 *
 * <p>It is also the one thing on the Overview that <em>cannot</em> be derived.
 * Collections, counts, progress and discussion all fall out of saves and states
 * that already exist; "which of these is the one we're doing" is a decision, and
 * a decision has to be stored or invented.
 *
 * <h2>Kinds</h2>
 * <ul>
 *   <li>{@link #SAVE} — {@code subject} is a save id. "Current program", and
 *       with a {@code {"date": …}} payload, the doc's cooking schedule
 *       ("Saturday: Lasagna") without a calendar feature existing.</li>
 *   <li>{@link #COLLECTION} — {@code subject} is a collection node id
 *       ({@code recommendation_list~anime}). Puts one collection above the
 *       others on a Space that has several.</li>
 * </ul>
 *
 * <p>The vocabulary lives here rather than in a check constraint, matching
 * {@code space_activity.type} and {@code saves.knowledge_type}: a new pin kind
 * is a client change, never a migration.
 *
 * <h2>Editor to write, member to read</h2>
 * <p>Pinning changes what everybody sees, so it needs {@code editor} — the same
 * bar as adding content, and the one the doc names. Unpinning takes the same
 * role rather than "whoever pinned it": a stale pin that only its author can
 * remove is a Space stuck with a program the author has stopped doing.
 */
@Service
public class SpacePinService {

    public static final String SAVE = "save";
    public static final String COLLECTION = "collection";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final SpaceService spaces;
    private final ObjectMapper objectMapper;
    private final TombstoneService tombstones;

    SpacePinService(JdbcClient jdbc, SpaceService spaces, ObjectMapper objectMapper,
                    TombstoneService tombstones) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.objectMapper = objectMapper;
        this.tombstones = tombstones;
    }

    /**
     * @param label     what the pinned thing is called <em>right now</em> —
     *                  resolved on read from the save's own
     *                  {@code structured_data}, never copied into the row at
     *                  pin time. A stored label goes stale the first time a
     *                  note is renamed, and a pin that lies about what it
     *                  points at is worse than no pin.
     * @param available whether the subject still exists and is still in this
     *                  Space. A save can be deleted, or moved back to its
     *                  owner's private library, long after it was pinned.
     */
    public record Pin(UUID id, String kind, String subject, String label, Map<String, Object> payload,
                      UUID createdBy, String createdByName, boolean available, Instant createdAt) {}

    // ------------------------------------------------------------------ read

    /**
     * Every pin in the Space, oldest first.
     *
     * <p>Unavailable pins are returned rather than filtered out, deliberately.
     * A pin that has stopped resolving is a thing an editor needs to see in
     * order to remove; silently hiding it leaves a row in the table that nobody
     * can reach and the Space quietly unable to pin that subject again (the
     * unique index still holds it).
     */
    @Transactional(readOnly = true)
    public List<Pin> list(UUID userId, UUID spaceId) {
        spaces.requireMember(userId, spaceId);
        return jdbc.sql("""
                        select p.id, p.kind, p.subject, p.payload::text as payload,
                               p.created_by, p.created_at,
                               coalesce(pr.display_name, 'A Weavr user') as created_by_name,
                               coalesce(s.structured_data ->> 'title',
                                        s.structured_data ->> 'name') as save_title,
                               (s.id is not null) as save_present
                        from space_pins p
                        left join profiles pr on pr.id = p.created_by
                        -- Only a `save` pin joins: a `collection` pin's subject
                        -- is a derived node id and matches no row anywhere, so
                        -- the join is written to fail for it rather than being
                        -- accidentally satisfied by a save whose id it isn't.
                        left join saves s on p.kind = 'save'
                                         and s.id::text = p.subject
                                         and s.space_id = p.space_id
                        where p.space_id = ?
                        order by p.created_at
                        """)
                .param(spaceId)
                .query((rs, row) -> {
                    String kind = rs.getString("kind");
                    boolean savePresent = rs.getBoolean("save_present");
                    return new Pin(
                            rs.getObject("id", UUID.class),
                            kind,
                            rs.getString("subject"),
                            SAVE.equals(kind) ? rs.getString("save_title") : null,
                            deserialise(rs.getString("payload")),
                            rs.getObject("created_by", UUID.class),
                            rs.getString("created_by_name"),
                            // A collection pin is always "available": its
                            // subject is a node id the client resolves against
                            // the tree it already has, and a node that produced
                            // nothing today simply renders no row.
                            !SAVE.equals(kind) || savePresent,
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    // ----------------------------------------------------------------- write

    /**
     * Pins something, or updates the payload of a pin that already exists.
     *
     * <p>The write is an upsert on {@code (space_id, kind, subject)} and a full
     * replace of {@code payload}, never a merge — the same rule every other
     * write in this codebase follows ({@code entity_states},
     * {@code save_item_states}, the digest row), and what makes a
     * double-tapped pin one pin without a line of dedupe logic.
     */
    @Transactional
    public Pin pin(UUID userId, UUID spaceId, String kind, String subject, Map<String, Object> payload) {
        spaces.requireRole(userId, spaceId, SpaceRole.EDITOR);
        String normalisedKind = kind == null || kind.isBlank() ? SAVE : kind.trim();

        if (SAVE.equals(normalisedKind)) {
            // Checked once, here, because it is checkable: a save pin points at
            // a row, and pinning a save that is not in this Space would be
            // either a mistake or a way to make a private save's title appear
            // on a shared screen. A collection pin has nothing to check
            // against — see the class javadoc.
            requireSaveInSpace(spaceId, subject);
        }

        UUID id = jdbc.sql("""
                        insert into space_pins (space_id, kind, subject, payload, created_by)
                        values (?, ?, ?, ?::jsonb, ?)
                        on conflict (space_id, kind, subject)
                            do update set payload = excluded.payload
                        returning id
                        """)
                .param(spaceId)
                .param(normalisedKind)
                .param(subject)
                .param(serialise(payload))
                .param(userId)
                .query(UUID.class)
                .single();

        spaces.recordActivity(spaceId, userId,
                SAVE.equals(normalisedKind) ? saveIdOrNull(subject) : null,
                "pinned", Map.of("kind", normalisedKind, "subject", subject));

        return list(userId, spaceId).stream()
                .filter(pin -> pin.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pin vanished after insert"));
    }

    @Transactional
    public void unpin(UUID userId, UUID spaceId, UUID pinId) {
        spaces.requireRole(userId, spaceId, SpaceRole.EDITOR);
        // The audience before the delete, as everywhere else — the members are
        // not going anywhere here, but the ordering is the rule rather than a
        // per-call judgement, and a shape copied from here into a case where it
        // matters should be the right one.
        List<UUID> audience = tombstones.membersOf(spaceId);

        int deleted = jdbc.sql("delete from space_pins where id = ? and space_id = ?")
                .param(pinId)
                .param(spaceId)
                .update();
        if (deleted == 0) {
            throw new NotFoundException("That pin is no longer there.");
        }
        tombstones.recordFor(audience, TombstoneService.SPACE_PIN, pinId.toString());
    }

    // ------------------------------------------------------------------ glue

    private void requireSaveInSpace(UUID spaceId, String subject) {
        UUID saveId = saveIdOrNull(subject);
        if (saveId == null) {
            throw new NotFoundException("That save isn't in this Space.");
        }
        boolean present = jdbc.sql("select 1 from saves where id = ? and space_id = ?")
                .param(saveId)
                .param(spaceId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!present) {
            throw new NotFoundException("That save isn't in this Space.");
        }
    }

    private static UUID saveIdOrNull(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private String serialise(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> deserialise(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            // A malformed payload costs this pin its detail, not the screen —
            // the same call `EntityStateService` and `SpaceKnowledgeService`
            // both make on their own jsonb columns.
            return Map.of();
        }
    }
}
