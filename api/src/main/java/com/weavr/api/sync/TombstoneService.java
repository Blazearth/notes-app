package com.weavr.api.sync;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record a delete leaves behind, so a client that was offline when it
 * happened can learn about it.
 *
 * <p>A delta carries rows that changed; it cannot carry a row that stopped
 * existing. There are seven hard-delete paths in this codebase and, before
 * V15, none of them left any trace — a Space deleted while a phone was in a
 * pocket would live in that phone's cache forever. See {@code V15__sync.sql}
 * and {@code docs/local-first.md}.
 *
 * <h2>Two rules that are easy to get wrong</h2>
 *
 * <p><b>The audience has to be read before the delete, not after.</b> Deleting
 * a Space cascades its {@code space_members} rows away, so a tombstone written
 * afterwards has nobody to address and every other member keeps the Space in
 * their cache indefinitely. Every call site here therefore resolves its
 * audience first — {@link #recordFor} takes the ids rather than working them
 * out.
 *
 * <p><b>A tombstone must never fail the delete it describes.</b> Same rule and
 * same mechanism as {@link com.weavr.api.space.SpaceService#recordActivity}:
 * {@code REQUIRES_NEW}, because Postgres aborts the whole transaction on any
 * failed statement, so a {@code catch} around a write that ran inside the
 * caller's transaction contains nothing at all.
 *
 * <h2>Composite ids</h2>
 * <p>Not everything deletable has a uuid, so {@code entity_id} is text and two
 * types encode a composite key with a {@code |} separator — chosen because it
 * appears in neither a uuid, an entity key ({@code "screen:blue box"}) nor an
 * item path ({@code "exercises[2]"}), where {@code :} appears in the first and
 * {@code [}/{@code ]} in the second:
 *
 * <ul>
 *   <li>{@link #SPACE_MEMBER} — {@code "<spaceId>|<userId>"}</li>
 *   <li>{@link #VOTE} — {@code "<saveId>|<userId>"}</li>
 *   <li>{@link #COLLECTION_OVERRIDE} — {@code "<overrideType>|<subjectKey>"}</li>
 * </ul>
 *
 * <p><b>{@code DELETE /v1/saves/&#123;id&#125;} used to write no tombstone at all.</b> This
 * class previously reasoned that no type was needed for a deleted save because no
 * delete path existed — "declaring a constant for a delete that does not exist
 * would be a thing to forget rather than a thing to build on." The delete endpoint
 * landed without the constant, which was precisely the forgetting that predicted.
 * Fixed by {@link #SAVE}: {@link com.weavr.api.save.SaveService#delete} now reads
 * {@link #audienceForSave} before deleting, same ordering rule as a Space.
 *
 * <p>There is still deliberately no type for a deleted item state:
 * {@code save_item_states} only ever loses rows by cascading from its save, so
 * the save's own tombstone is what a client needs.
 */
@Service
public class TombstoneService {

    private static final Logger log = LoggerFactory.getLogger(TombstoneService.class);

    public static final String SPACE = "space";
    public static final String SPACE_MEMBER = "space_member";
    /**
     * A permanently deleted save. Recorded against {@link #audienceForSave},
     * read <i>before</i> the delete for the same reason a Space's audience is
     * — {@code save_item_states}, comments and votes cascade away with the
     * save, and a shared save leaves every Space member holding a dead
     * reference until this fires. See {@link com.weavr.api.save.SaveService#delete}.
     */
    public static final String SAVE = "save";
    public static final String COMMENT = "comment";
    /**
     * S3's entity-level comment. A separate type from {@link #COMMENT} because
     * the two live in different tables with different audiences — a save comment
     * is addressed to everyone who can see the save (its owner included, even
     * for a private save), an entity comment to the Space's members and nobody
     * else.
     */
    public static final String ENTITY_COMMENT = "entity_comment";
    public static final String VOTE = "vote";
    /** S4's pin. Audience is the Space's members, read before the delete. */
    public static final String SPACE_PIN = "space_pin";
    public static final String SHOPPING_ITEM = "shopping_item";
    public static final String COLLECTION_OVERRIDE = "collection_override";

    /** The separator in the two composite {@code entity_id} forms above. */
    public static final String KEY_SEPARATOR = "|";

    private final JdbcClient jdbc;

    TombstoneService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One deletion, one audience member. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String entityType, String entityId) {
        recordFor(List.of(userId), entityType, entityId);
    }

    /**
     * One deletion, several audience members — a Space's members learning that
     * somebody left, or that the Space itself is gone.
     *
     * <p>Silently does nothing for an empty audience, which is the ordinary
     * case for a private save's comment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFor(Collection<UUID> userIds, String entityType, String entityId) {
        if (userIds.isEmpty()) {
            return;
        }
        try {
            for (UUID userId : userIds) {
                jdbc.sql("""
                                insert into tombstones (user_id, entity_type, entity_id)
                                values (?, ?, ?)
                                """)
                        .param(userId)
                        .param(entityType)
                        .param(entityId)
                        .update();
            }
        } catch (RuntimeException e) {
            // A missing tombstone costs one client a stale row until its next
            // full pull. Failing the delete itself would cost the user the
            // action they asked for.
            log.warn("Could not record {} tombstone for {}: {}", entityType, entityId, e.toString());
        }
    }

    /**
     * Everyone who can see a save: its owner, plus every member of the Space it
     * sits in. The audience for a comment or a vote on it.
     *
     * <p>Read in one query rather than two so it can be called before a delete
     * without holding the caller's transaction open any longer than necessary.
     */
    @Transactional(readOnly = true)
    public List<UUID> audienceForSave(UUID saveId) {
        return jdbc.sql("""
                        select s.user_id from saves s where s.id = ?
                        union
                        select m.user_id
                        from saves s
                        join space_members m on m.space_id = s.space_id
                        where s.id = ? and s.space_id is not null
                        """)
                .param(saveId)
                .param(saveId)
                .query(UUID.class)
                .list();
    }

    /** The members of a Space, read while they still exist. */
    @Transactional(readOnly = true)
    public List<UUID> membersOf(UUID spaceId) {
        return jdbc.sql("select user_id from space_members where space_id = ?")
                .param(spaceId)
                .query(UUID.class)
                .list();
    }
}
