package com.weavr.api.space;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.ForbiddenException;
import com.weavr.api.sync.TombstoneService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discussion attached to a merged entity — S3 of {@code docs/knowledge-spaces.md}.
 *
 * <p>S2 surfaced what already existed: the latest {@code save_comments} across
 * the Space's saves, which is one indexed query and cost no schema. This is the
 * step the doc gated behind that one, and the reason it is a different table
 * rather than a flag on the old one: <b>"Blue Box starts slow" is a remark about
 * Blue Box, not about whichever Reel mentioned it.</b> Blue Box is assembled
 * from several members' saves, so hanging the remark off one of them files it
 * under a source the next reader has no reason to open — and if that source
 * later leaves the Space, the discussion leaves with it.
 *
 * <h2>Space-scoped, where state is global — deliberately</h2>
 * <p>{@code entity_states} (V13) is keyed {@code (user_id, entity_key)} with no
 * Space dimension, and S2's disclosure decision leans on that: completing Your
 * Name is a fact about the person, so it shows in every Space whose knowledge
 * contains the title. A comment is the opposite kind of thing. It was said in a
 * room, to the people in it, and carrying it into another Space that happens to
 * hold the same entity would disclose a conversation rather than a status. So
 * {@code space_id} is part of this key and is absent from that one. The two
 * tables answering the same-looking question differently is the design.
 *
 * <h2>Why nothing here validates the entity key</h2>
 * <p>An entity is derived, not stored — {@link com.weavr.api.collection.Entities#key}
 * over whatever the Space's saves merge to today — so there is no row to check
 * against and no foreign key to declare. A comment can therefore outlive its
 * entity (every save mentioning it leaves the Space) and simply becomes
 * unreachable, exactly as a {@code collection_overrides} row does. Validating
 * against the derived set on write would be worse, not better: it would make
 * commenting cost a full merge, and it would still be a race.
 */
@Service
public class EntityCommentService {

    /** How many the Overview's discussion block carries — see {@link SpaceKnowledgeService}. */
    private static final int MAX_RECENT = 50;

    private final JdbcClient jdbc;
    private final SpaceService spaces;
    private final TombstoneService tombstones;

    EntityCommentService(JdbcClient jdbc, SpaceService spaces, TombstoneService tombstones) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.tombstones = tombstones;
    }

    /**
     * One comment in an entity's thread.
     *
     * @param mine whether the caller wrote it — what decides if a delete
     *             affordance shows, mirroring {@code SaveSocialService.Comment}
     *             so the client renders both kinds of thread with one component
     */
    public record EntityComment(UUID id, String entityKey, UUID userId, String displayName,
                                String body, Instant createdAt, boolean mine) {}

    // ------------------------------------------------------------------ read

    /**
     * One entity's thread, oldest first — a conversation reads downwards.
     *
     * <p>Membership first, always: a non-member gets
     * {@link com.weavr.api.common.NotFoundException} from
     * {@link SpaceService#requireMember} rather than an empty list, so this
     * cannot become a way to probe which Spaces exist or what is in them.
     */
    @Transactional(readOnly = true)
    public List<EntityComment> list(UUID userId, UUID spaceId, String entityKey) {
        spaces.requireMember(userId, spaceId);
        return jdbc.sql("""
                        select c.id, c.entity_key, c.user_id, c.body, c.created_at,
                               coalesce(p.username, p.display_name, 'Weavr user') as display_name
                        from entity_comments c
                        left join profiles p on p.id = c.user_id
                        where c.space_id = ? and c.entity_key = ?
                        order by c.created_at
                        """)
                .param(spaceId)
                .param(entityKey)
                .query((rs, row) -> map(rs, userId))
                .list();
    }

    /**
     * How many comments each of a set of entities carries.
     *
     * <p>One query for a whole screen rather than one per row — the same reason
     * {@link SpaceKnowledgeService#memberStates} and
     * {@code SaveItemStateService.statesForSaves} are batched: N is the size of
     * a merged collection, which grows with the Space and is bounded by nothing.
     *
     * <p>Entities nobody has commented on are simply absent from the map, so a
     * caller reads {@code getOrDefault(key, 0)} and no row costs anything.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> counts(UUID spaceId, Collection<String> entityKeys) {
        if (entityKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> byEntity = new LinkedHashMap<>();
        jdbc.sql("""
                        select entity_key, count(*) as n
                        from entity_comments
                        where space_id = ? and entity_key = any(?::text[])
                        group by entity_key
                        """)
                .param(spaceId)
                .param(entityKeys.toArray(String[]::new))
                .query((rs, row) -> Map.entry(rs.getString("entity_key"), rs.getInt("n")))
                .list()
                .forEach(entry -> byEntity.put(entry.getKey(), entry.getValue()));
        return byEntity;
    }

    /**
     * The latest entity comments across the whole Space, newest first.
     *
     * <p>Feeds the Overview's discussion block alongside the save comments S2
     * already showed. Both kinds appear there because "recent discussion" is one
     * question — splitting the block by which table a remark landed in would ask
     * the reader to care about a distinction that exists for storage reasons.
     */
    @Transactional(readOnly = true)
    public List<EntityComment> recent(UUID spaceId, int limit) {
        return jdbc.sql("""
                        select c.id, c.entity_key, c.user_id, c.body, c.created_at,
                               coalesce(p.username, p.display_name, 'Weavr user') as display_name
                        from entity_comments c
                        left join profiles p on p.id = c.user_id
                        where c.space_id = ?
                        order by c.created_at desc
                        limit ?
                        """)
                .param(spaceId)
                .param(Math.clamp(limit, 1, MAX_RECENT))
                .query((rs, row) -> map(rs, null))
                .list();
    }

    // ----------------------------------------------------------------- write

    /**
     * Adds a comment.
     *
     * <p>{@code viewer} is enough, not {@code editor} — the same call
     * {@link SaveSocialService#addComment} makes, and for the same reason: a
     * free user pulled into someone's Space is a participant rather than an
     * audience, which is the whole growth loop. Only adding *content* needs
     * editor.
     */
    @Transactional
    public EntityComment add(UUID userId, UUID spaceId, String entityKey, String body) {
        spaces.requireMember(userId, spaceId);
        UUID id = jdbc.sql("""
                        insert into entity_comments (space_id, entity_key, user_id, body)
                        values (?, ?, ?, ?)
                        returning id
                        """)
                .param(spaceId)
                .param(entityKey)
                .param(userId)
                .param(body.trim())
                .query(UUID.class)
                .single();

        // No `save_id`: the whole point is that this remark is not about one
        // save. The payload carries the entity key so the feed can say what was
        // discussed without a second lookup — `space_activity.payload` is jsonb
        // for exactly this kind of type-specific detail.
        spaces.recordActivity(spaceId, userId, null, "commented", Map.of("entityKey", entityKey));

        return list(userId, spaceId, entityKey).stream()
                .filter(comment -> comment.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("comment vanished after insert"));
    }

    /**
     * Your own comment, or any comment if you own the Space — the second is what
     * lets an owner deal with something they should not have to host.
     * {@link SaveSocialService#deleteComment} makes the identical call.
     */
    @Transactional
    public void delete(UUID userId, UUID spaceId, UUID commentId) {
        SpaceRole role = spaces.requireMember(userId, spaceId);
        boolean isOwner = role == SpaceRole.OWNER;

        // The audience is read before the delete, not after — the standing rule
        // for every tombstone in this codebase. Here the audience is the Space's
        // members, who are not going anywhere, so the ordering is defensive
        // rather than load-bearing; writing it the other way would still be a
        // shape somebody copies into a case where it matters.
        List<UUID> audience = tombstones.membersOf(spaceId);

        int deleted = isOwner
                ? jdbc.sql("delete from entity_comments where id = ? and space_id = ?")
                        .param(commentId).param(spaceId).update()
                : jdbc.sql("delete from entity_comments where id = ? and space_id = ? and user_id = ?")
                        .param(commentId).param(spaceId).param(userId).update();

        if (deleted == 0) {
            // Someone else's comment, or already gone. A 403 rather than a 404
            // because the caller is looking at it in a list they just read —
            // pretending it does not exist would be nonsense. Same call
            // `SaveSocialService` makes.
            throw new ForbiddenException("You can only delete your own comments.");
        }

        tombstones.recordFor(audience, TombstoneService.ENTITY_COMMENT, commentId.toString());
    }

    // ------------------------------------------------------------------ glue

    private static EntityComment map(java.sql.ResultSet rs, UUID viewerId) throws java.sql.SQLException {
        UUID author = rs.getObject("user_id", UUID.class);
        return new EntityComment(
                rs.getObject("id", UUID.class),
                rs.getString("entity_key"),
                author,
                rs.getString("display_name"),
                rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(),
                viewerId != null && author.equals(viewerId));
    }
}
