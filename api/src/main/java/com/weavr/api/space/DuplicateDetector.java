package com.weavr.api.space;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds saves in a shared Space that are probably the same thing.
 *
 * <h2>Why this is worth building</h2>
 * <p>Two people save the same restaurant — one from a Reel, one from a Google
 * Maps link, one calling it "Noma" and the other "Noma 2.0 Copenhagen". Exact
 * URL matching, which is as far as every competitor in the teardown goes, sees
 * nothing in common. The embedding does, because it was built from
 * {@code structured_data} rather than from the URL or the raw caption.
 *
 * <h2>A suggestion, never a merge</h2>
 * <p>Cosine distance is evidence, not proof, and silently merging someone
 * else's save is not something the client can undo. So this writes a row to
 * {@code save_duplicates} that the Space can show and a member can accept or
 * dismiss.
 *
 * <h2>The threshold is a guess, and is stated as one</h2>
 * <p>{@code max-distance} defaults to 0.15, argued rather than measured: the
 * search half found real query hits landing at 0.27–0.37, and two saves of the
 * <em>same</em> thing must be far closer than a query that merely describes it.
 * It sits in configuration for the same reason the OCR confidence floor and the
 * search cutoff do — the honest thing is one number in one place that a real
 * corpus can correct, not a constant buried in a query.
 */
@Service
public class DuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetector.class);

    private final JdbcClient jdbc;
    private final SpaceProperties props;
    private final SpaceService spaces;

    DuplicateDetector(JdbcClient jdbc, SpaceProperties props, SpaceService spaces) {
        this.jdbc = jdbc;
        this.props = props;
        this.spaces = spaces;
    }

    public record Suggestion(UUID id, UUID saveId, String saveTitle,
                             UUID duplicateOf, String duplicateTitle,
                             double distance, String status) {
    }

    /**
     * Compares a freshly embedded save against everything else in its Space.
     *
     * <p>Only saves of the same {@code knowledge_type} are considered: a recipe
     * and a restaurant are never the same thing however close their vectors
     * land, and the type is already known for free.
     */
    @Transactional
    public void detect(UUID saveId) {
        if (!props.duplicateDetectionEnabled()) {
            return;
        }

        record Row(UUID spaceId, String knowledgeType, UUID userId) {
        }
        Row save = jdbc.sql("""
                        select space_id, knowledge_type, user_id
                        from saves
                        where id = ? and space_id is not null and embedding is not null
                        """)
                .param(saveId)
                .query((rs, i) -> new Row(
                        rs.getObject("space_id", UUID.class),
                        rs.getString("knowledge_type"),
                        rs.getObject("user_id", UUID.class)))
                .optional()
                .orElse(null);

        if (save == null) {
            // Private, unembedded, or gone. Nothing to compare against.
            return;
        }

        // `<=>` is cosine distance. The vectors are normalised client-side
        // (gemini-embedding-001 truncates rather than re-embeds for reduced
        // dimensions, so a 1536-d vector arrives un-normalised at L2 0.69),
        // but cosine is scale-invariant anyway — the normalisation matters for
        // the operators that are not, should this ever change to <-> or <#>.
        List<Object[]> matches = jdbc.sql("""
                        select other.id, (other.embedding <=> mine.embedding) as distance
                        from saves other
                        join saves mine on mine.id = ?
                        where other.space_id = mine.space_id
                          and other.id <> mine.id
                          and other.knowledge_type = mine.knowledge_type
                          and other.embedding is not null
                          and (other.embedding <=> mine.embedding) <= ?
                        order by distance
                        limit ?
                        """)
                .param(saveId)
                .param(props.duplicateMaxDistance())
                .param(props.duplicateMaxSuggestions())
                .query((rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getDouble("distance")})
                .list();

        for (Object[] match : matches) {
            UUID otherId = (UUID) match[0];
            double distance = (double) match[1];
            record(save.spaceId(), saveId, otherId, distance);
            log.info("Save {} looks like a duplicate of {} in space {} (distance {})",
                    saveId, otherId, save.spaceId(), String.format("%.4f", distance));
            spaces.recordActivity(save.spaceId(), save.userId(), saveId,
                    "duplicate_suggested", Map.of("duplicateOf", otherId.toString()));
        }
    }

    /**
     * One row per pair, and a pair dismissed once stays dismissed.
     *
     * <p>{@code do nothing} rather than an upsert: re-running detection must not
     * resurrect a suggestion someone has already said no to. That is the whole
     * difference between a helpful prompt and a nag.
     */
    private void record(UUID spaceId, UUID saveId, UUID duplicateOf, double distance) {
        jdbc.sql("""
                        insert into save_duplicates (space_id, save_id, duplicate_of, distance)
                        values (?, ?, ?, ?)
                        on conflict (save_id, duplicate_of) do nothing
                        """)
                .param(spaceId)
                .param(saveId)
                .param(duplicateOf)
                .param(distance)
                .update();
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(UUID userId, UUID spaceId) {
        spaces.requireMember(userId, spaceId);
        return jdbc.sql("""
                        select d.id, d.save_id, d.duplicate_of, d.distance, d.status,
                               coalesce(a.structured_data ->> 'title',
                                        a.structured_data ->> 'name') as save_title,
                               coalesce(b.structured_data ->> 'title',
                                        b.structured_data ->> 'name') as duplicate_title
                        from save_duplicates d
                        join saves a on a.id = d.save_id
                        join saves b on b.id = d.duplicate_of
                        where d.space_id = ? and d.status = 'suggested'
                        order by d.distance
                        """)
                .param(spaceId)
                .query((rs, i) -> new Suggestion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("save_id", UUID.class),
                        rs.getString("save_title"),
                        rs.getObject("duplicate_of", UUID.class),
                        rs.getString("duplicate_title"),
                        rs.getDouble("distance"),
                        rs.getString("status")))
                .list();
    }

    /**
     * "These are not the same thing" — an editor's call, since it changes what
     * everyone in the Space sees.
     */
    @Transactional
    public void dismiss(UUID userId, UUID spaceId, UUID suggestionId) {
        spaces.requireRole(userId, spaceId, SpaceRole.EDITOR);
        jdbc.sql("update save_duplicates set status = 'dismissed' where id = ? and space_id = ?")
                .param(suggestionId)
                .param(spaceId)
                .update();
    }

    /**
     * "Yes, these are the same" — the newer save leaves the Space and returns
     * to its owner's private library.
     *
     * <p>Deliberately not a delete. The two saves belong to different people
     * and may carry different notes, comments and lifecycle state; destroying
     * one to tidy a list is not a trade the person who saved it agreed to.
     */
    @Transactional
    public void merge(UUID userId, UUID spaceId, UUID suggestionId) {
        spaces.requireRole(userId, spaceId, SpaceRole.EDITOR);
        UUID saveId = jdbc.sql("""
                        select save_id from save_duplicates where id = ? and space_id = ? and status = 'suggested'
                        """)
                .param(suggestionId)
                .param(spaceId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new com.weavr.api.common.NotFoundException(
                        "That suggestion is no longer open."));

        // No tombstone here, deliberately: this is an UPDATE, not a delete, so
        // the `saves_set_updated_at` trigger bumps `updated_at` and the ordinary
        // delta already carries the row — with `spaceId` now null, which is
        // exactly what the owner's client needs to see. See V15__sync.sql.
        jdbc.sql("update saves set space_id = null where id = ?").param(saveId).update();
        jdbc.sql("update save_duplicates set status = 'merged' where id = ?").param(suggestionId).update();
        log.info("Duplicate {} merged out of space {} by {}", saveId, spaceId, userId);
    }
}
