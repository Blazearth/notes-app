package com.weavr.api.space;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.common.ForbiddenException;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.sync.TombstoneService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments and votes, and the visibility rule they both inherit.
 *
 * <p>Visibility is the save's: you can comment on it if you can see it, which
 * means you own it or you are in the Space it belongs to. That single rule
 * lives in {@link #requireVisible} and nowhere else — duplicating it per
 * endpoint is how one of them ends up subtly more permissive than the rest.
 */
@Service
public class SaveSocialService {

    public record Comment(UUID id, UUID userId, String displayName, String body,
                          Instant createdAt, boolean mine) {
    }

    private final JdbcClient jdbc;
    private final SpaceService spaces;
    private final TombstoneService tombstones;

    SaveSocialService(JdbcClient jdbc, SpaceService spaces, TombstoneService tombstones) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.tombstones = tombstones;
    }

    /**
     * @return the save's space id, or null when it is a private save
     * @throws NotFoundException when the caller cannot see it at all —
     *                           never a 403, which would confirm it exists
     */
    private UUID requireVisible(UUID userId, UUID saveId) {
        record Row(UUID userId, UUID spaceId) {
        }
        Row row = jdbc.sql("select user_id, space_id from saves where id = ?")
                .param(saveId)
                .query((rs, i) -> new Row(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("space_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new NotFoundException("Save not found"));

        if (row.userId().equals(userId)) {
            return row.spaceId();
        }
        if (row.spaceId() != null && spaces.roleOf(userId, row.spaceId()).isPresent()) {
            return row.spaceId();
        }
        throw new NotFoundException("Save not found");
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(UUID userId, UUID saveId) {
        requireVisible(userId, saveId);
        return jdbc.sql("""
                        select c.id, c.user_id, c.body, c.created_at,
                               coalesce(p.username, p.display_name, 'Weavr user') as display_name
                        from save_comments c
                        join profiles p on p.id = c.user_id
                        where c.save_id = ?
                        order by c.created_at
                        """)
                .param(saveId)
                .query((rs, i) -> {
                    UUID author = rs.getObject("user_id", UUID.class);
                    return new Comment(
                            rs.getObject("id", UUID.class),
                            author,
                            rs.getString("display_name"),
                            rs.getString("body"),
                            rs.getTimestamp("created_at").toInstant(),
                            author.equals(userId));
                })
                .list();
    }

    @Transactional
    public Comment addComment(UUID userId, UUID saveId, String body) {
        UUID spaceId = requireVisible(userId, saveId);
        // A viewer can read and discuss; that is what makes an invited free
        // user a participant rather than an audience, which is the point of
        // the growth loop. Only adding *content* needs editor.
        UUID id = jdbc.sql("""
                        insert into save_comments (save_id, user_id, body) values (?, ?, ?)
                        returning id
                        """)
                .param(saveId)
                .param(userId)
                .param(body.trim())
                .query(UUID.class)
                .single();

        spaces.recordActivity(spaceId, userId, saveId, "commented", Map.of());

        return comments(userId, saveId).stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("comment vanished after insert"));
    }

    /**
     * Your own comment, or any comment if you own the Space — the second is
     * what makes an owner able to deal with something they should not have to
     * host.
     */
    @Transactional
    public void deleteComment(UUID userId, UUID saveId, UUID commentId) {
        UUID spaceId = requireVisible(userId, saveId);
        boolean isOwner = spaceId != null
                && spaces.roleOf(userId, spaceId).filter(r -> r == SpaceRole.OWNER).isPresent();

        int deleted = isOwner
                ? jdbc.sql("delete from save_comments where id = ? and save_id = ?")
                        .param(commentId).param(saveId).update()
                : jdbc.sql("delete from save_comments where id = ? and save_id = ? and user_id = ?")
                        .param(commentId).param(saveId).param(userId).update();

        if (deleted == 0) {
            // Someone else's comment, or already gone. Both are a 403 rather
            // than a 404 because the caller can see the comment in the list
            // they just read — pretending it does not exist would be nonsense.
            throw new ForbiddenException("You can only delete your own comments.");
        }

        // Written for everyone who could see the comment, not just the deleter.
        // `save_comments` is deliberately outside the delta (see
        // docs/local-first.md — scoping "comments on saves I can see" is a join
        // across Spaces, and comments are a detail-screen read), so no client
        // consumes this yet; it exists so the deletion record is complete
        // rather than complete for six of seven paths.
        tombstones.recordFor(tombstones.audienceForSave(saveId), TombstoneService.COMMENT,
                commentId.toString());
    }

    /**
     * Sets, rather than increments.
     *
     * <p>The tempting design is a tally on the save and {@code score = score +
     * 1}. It is the same mistake the shopping list made by storing a running
     * total instead of each contributor's own quantity: correct exactly once,
     * and wrong the moment anything is retried or replayed. A row per (save,
     * user) makes the score a {@code sum} that depends only on who voted, so a
     * double-submitted request cannot inflate it.
     *
     * @param value 1, -1, or 0 to clear
     * @return the save's new score
     */
    @Transactional
    public int vote(UUID userId, UUID saveId, int value) {
        UUID spaceId = requireVisible(userId, saveId);

        if (value == 0) {
            int cleared = jdbc.sql("delete from save_votes where save_id = ? and user_id = ?")
                    .param(saveId).param(userId).update();
            // Only the voter held the row, so only the voter's cache can hold a
            // copy of it — unlike a comment, a vote is not visible to anyone
            // else as a row of its own, just as a contribution to the score.
            // Same "no consumer yet" caveat as the comment tombstone above.
            if (cleared > 0) {
                tombstones.record(userId, TombstoneService.VOTE,
                        saveId + TombstoneService.KEY_SEPARATOR + userId);
            }
        } else if (value == 1 || value == -1) {
            // Read before write, so re-sending the same vote does not put a
            // second line in the Space's feed. Setting a value you already
            // hold is one action from the user's point of view, and the
            // sparse-events rule is only worth anything if it holds here.
            Integer previous = jdbc
                    .sql("select value from save_votes where save_id = ? and user_id = ?")
                    .param(saveId).param(userId)
                    .query(Integer.class)
                    .optional()
                    .orElse(null);

            jdbc.sql("""
                            insert into save_votes (save_id, user_id, value) values (?, ?, ?)
                            on conflict (save_id, user_id) do update set value = excluded.value
                            """)
                    .param(saveId).param(userId).param(value).update();

            if (previous == null || previous != value) {
                spaces.recordActivity(spaceId, userId, saveId, "voted", Map.of("value", value));
            }
        } else {
            throw new IllegalArgumentException("A vote must be 1, -1, or 0 to clear it.");
        }

        return score(saveId);
    }

    /**
     * The save's score and the caller's own vote, for a screen opening on it.
     *
     * <p>Without this the client could only learn either one by casting a vote,
     * so the control rendered unvoted at a score of nothing — and the first tap
     * on a save already at +4 showed +1.
     */
    @Transactional(readOnly = true)
    public VoteState voteState(UUID userId, UUID saveId) {
        requireVisible(userId, saveId);
        int mine = jdbc.sql("select value from save_votes where save_id = ? and user_id = ?")
                .param(saveId).param(userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return new VoteState(score(saveId), mine);
    }

    public record VoteState(int score, int myVote) {
    }

    @Transactional(readOnly = true)
    public int score(UUID saveId) {
        Integer sum = jdbc.sql("select coalesce(sum(value), 0) from save_votes where save_id = ?")
                .param(saveId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return sum == null ? 0 : sum;
    }
}
