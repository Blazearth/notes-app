package com.weavr.api.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.save.Save;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.dto.SaveResponse;
import com.weavr.api.space.SpaceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * One request that tells a client everything that changed since it last asked.
 *
 * <p>This replaces seven full-table fetches on every app launch with one
 * windowed read. Before it, "sync" meant fetch-and-replace: walk
 * {@code GET /v1/saves} to exhaustion, re-list every Space, re-list every
 * Space's members, and harvest entity state out of three collection endpoints.
 * All of that is now one call whose size is proportional to what actually
 * changed, which on a warm client is nothing.
 *
 * <h2>Two things this endpoint deliberately does not do</h2>
 *
 * <p><b>It does not widen visibility.</b> {@code saves} is scoped to
 * {@code user_id = ?} — the caller's own saves, exactly what
 * {@code GET /v1/saves} returns — not "own plus every Space I am in". A Space's
 * saves keep arriving from {@code GET /v1/spaces/{id}/saves} on demand, as
 * today. Widening it would be a new visibility rule to get right (and to un-get
 * right: a save that leaves a Space would have to be *removed* from every other
 * member's cache, which is a tombstone this endpoint does not write), and a
 * delta that changes what a client can see is not a drop-in replacement for the
 * full pull it stands in for.
 *
 * <p><b>It does not carry everything.</b> {@code space_activity},
 * {@code space_invites}, {@code save_votes}, {@code save_duplicates},
 * {@code digests} and {@code save_comments} are all out: the first five have no
 * {@code updated_at} at all, and comments would need "comments on saves I can
 * see", which is a join across Spaces for a detail-screen read. All of them stay
 * on-demand fetches. {@code shopping_list_items} is out too, for a different
 * reason: the client stores the list in the server's aisle-then-name order, and
 * a stream of individual item rows cannot reconstruct that ordering — so the
 * shopping list stays a small full pull, while its <em>deletions</em> do ride
 * the tombstone list, which is the half a full pull handles worst.
 *
 * <h2>The window</h2>
 *
 * <p>{@link SyncWindow} owns the boundary logic and the reasoning behind it. The
 * consequence for this class: each entity type is read twice at most — once with
 * {@code limit + 1} to detect overflow, and once more with an inclusive
 * {@code <= until} bound if any type did overflow.
 */
@Service
public class SyncService {

    /** Also the default, and what the client's `PAGE_SIZE` sends. */
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final SaveRepository saves;
    private final SpaceService spaces;
    private final ObjectMapper objectMapper;

    SyncService(JdbcClient jdbc, SaveRepository saves, SpaceService spaces, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.saves = saves;
        this.spaces = spaces;
        this.objectMapper = objectMapper;
    }

    /**
     * @param since {@code null} for a first sync — the same code path, just a
     *              window that starts at the epoch. There is no separate
     *              bootstrap.
     */
    @Transactional(readOnly = true)
    public SyncResponse since(UUID userId, Instant since, int limit) {
        Instant from = since == null ? Instant.EPOCH : since;
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);

        // Pass 1: limit + 1 rows per type, to learn which types have more.
        List<Save> savePage = saves.findByUserIdAndUpdatedAtGreaterThanOrderByUpdatedAtAsc(
                userId, from, PageRequest.of(0, bounded + 1));
        List<Keyed<UUID>> spacePage = changedSpaces(userId, from, null, bounded + 1);
        List<Keyed<UUID>> memberPage = changedMemberSpaces(userId, from, null, bounded + 1);
        List<Keyed<SyncResponse.ItemState>> itemPage = changedItemStates(userId, from, null, bounded + 1);
        List<Keyed<SyncResponse.EntityState>> entityPage = changedEntityStates(userId, from, null, bounded + 1);
        List<Keyed<SyncResponse.Override>> overridePage = changedOverrides(userId, from, null, bounded + 1);
        List<Keyed<SyncResponse.Deletion>> deletionPage = deletions(userId, from, null, bounded + 1);

        SyncWindow.Window window = SyncWindow.cap(
                List.of(
                        savePage.stream().map(Save::getUpdatedAt).toList(),
                        cursors(spacePage),
                        cursors(memberPage),
                        cursors(itemPage),
                        cursors(entityPage),
                        cursors(overridePage),
                        cursors(deletionPage)),
                from, bounded);

        // Pass 2, only when a type overflowed: re-read every type bounded by the
        // agreed `until`, so the page ends on a whole-timestamp boundary rather
        // than inside a group of rows one transaction wrote together.
        if (window.capped()) {
            Instant until = window.until();
            savePage = saves.findByUserIdAndUpdatedAtGreaterThanAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                    userId, from, until);
            spacePage = changedSpaces(userId, from, until, 0);
            memberPage = changedMemberSpaces(userId, from, until, 0);
            itemPage = changedItemStates(userId, from, until, 0);
            entityPage = changedEntityStates(userId, from, until, 0);
            overridePage = changedOverrides(userId, from, until, 0);
            deletionPage = deletions(userId, from, until, 0);
        }

        return new SyncResponse(
                window.until(),
                window.hasMore(),
                // The 1-arg overload, on purpose: item states travel as their own
                // list, and the client's store holds them in a separate table.
                savePage.stream().map(SaveResponse::from).toList(),
                spacePayloads(userId, spacePage),
                memberPayloads(userId, memberPage),
                rows(itemPage),
                rows(entityPage),
                rows(overridePage),
                rows(deletionPage));
    }

    // ------------------------------------------------------------- helpers

    /** A row plus the cursor it sits at, so the window logic stays type-free. */
    private record Keyed<T>(Instant at, T row) {
    }

    private static <T> List<Instant> cursors(List<Keyed<T>> page) {
        return page.stream().map(Keyed::at).toList();
    }

    private static <T> List<T> rows(List<Keyed<T>> page) {
        return page.stream().map(Keyed::row).toList();
    }

    // Each query below takes `(until, limit)` in one of two shapes: pass 1 sends
    // `until = null` with `limit = pageSize + 1` to detect overflow, pass 2 sends
    // a real `until` with `limit = 0` (unpaged), because a limit in pass 2 would
    // put back the mid-group split the boundary exists to avoid. The `limit` is
    // interpolated rather than bound because it is an int the service computed,
    // never a value from the request.

    // ------------------------------------------------------------- spaces

    /**
     * Which Spaces changed, as ids — the payload comes from
     * {@link SpaceService#listForUser}, so the {@code Space} shape (member and
     * save counts, the caller's own role, last activity) has exactly one
     * definition. A second copy of that select here would drift from it the
     * first time either changed.
     */
    private List<Keyed<UUID>> changedSpaces(UUID userId, Instant since, Instant until, int limit) {
        String sql = """
                select s.id, s.updated_at
                from spaces s
                join space_members m on m.space_id = s.id
                where m.user_id = ? and s.updated_at > ?
                """
                + (until == null ? "" : " and s.updated_at <= ?")
                + " order by s.updated_at asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("id", UUID.class)))
                .list();
    }

    private List<SpaceService.Space> spacePayloads(UUID userId, List<Keyed<UUID>> changed) {
        if (changed.isEmpty()) {
            return List.of();
        }
        Set<UUID> wanted = new LinkedHashSet<>(rows(changed));
        return spaces.listForUser(userId).stream().filter(s -> wanted.contains(s.id())).toList();
    }

    /**
     * Which Spaces had <em>any</em> member row change. The response carries each
     * one's full member list rather than the changed rows — see
     * {@link SyncResponse#spaceMembers()}.
     */
    private List<Keyed<UUID>> changedMemberSpaces(UUID userId, Instant since, Instant until, int limit) {
        String sql = """
                select m.space_id, max(m.updated_at) as updated_at
                from space_members m
                where m.space_id in (select space_id from space_members where user_id = ?)
                  and m.updated_at > ?
                """
                + (until == null ? "" : " and m.updated_at <= ?")
                + " group by m.space_id order by max(m.updated_at) asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("space_id", UUID.class)))
                .list();
    }

    private List<SyncResponse.SpaceMembers> memberPayloads(UUID userId, List<Keyed<UUID>> changed) {
        List<SyncResponse.SpaceMembers> out = new ArrayList<>();
        for (UUID spaceId : new LinkedHashSet<>(rows(changed))) {
            // `members` re-checks membership, which is what we want: a Space the
            // caller has just been removed from must not leak its member list
            // through the same page that reports the removal.
            try {
                out.add(new SyncResponse.SpaceMembers(spaceId, spaces.members(userId, spaceId)));
            } catch (RuntimeException ignored) {
                // Not a member (any more). The `space` tombstone is what tells
                // the client; an empty member list would say nothing.
            }
        }
        return out;
    }

    // -------------------------------------------------------------- states

    private List<Keyed<SyncResponse.ItemState>> changedItemStates(UUID userId, Instant since,
                                                                 Instant until, int limit) {
        String sql = """
                select save_id, item_path, state::text as state, updated_at
                from save_item_states
                where user_id = ? and updated_at > ?
                """
                + (until == null ? "" : " and updated_at <= ?")
                + " order by updated_at asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("updated_at").toInstant(),
                        new SyncResponse.ItemState(
                                rs.getObject("save_id", UUID.class),
                                rs.getString("item_path"),
                                deserialise(rs.getString("state")))))
                .list();
    }

    private List<Keyed<SyncResponse.EntityState>> changedEntityStates(UUID userId, Instant since,
                                                                     Instant until, int limit) {
        String sql = """
                select entity_key, state::text as state, updated_at
                from entity_states
                where user_id = ? and updated_at > ?
                """
                + (until == null ? "" : " and updated_at <= ?")
                + " order by updated_at asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("updated_at").toInstant(),
                        new SyncResponse.EntityState(
                                rs.getString("entity_key"),
                                deserialise(rs.getString("state")))))
                .list();
    }

    private List<Keyed<SyncResponse.Override>> changedOverrides(UUID userId, Instant since,
                                                               Instant until, int limit) {
        String sql = """
                select override_type, subject_key, payload::text as payload, updated_at
                from collection_overrides
                where user_id = ? and updated_at > ?
                """
                + (until == null ? "" : " and updated_at <= ?")
                + " order by updated_at asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("updated_at").toInstant(),
                        new SyncResponse.Override(
                                rs.getString("override_type"),
                                rs.getString("subject_key"),
                                deserialise(rs.getString("payload")))))
                .list();
    }

    // ---------------------------------------------------------- tombstones

    private List<Keyed<SyncResponse.Deletion>> deletions(UUID userId, Instant since,
                                                         Instant until, int limit) {
        String sql = """
                select entity_type, entity_id, deleted_at
                from tombstones
                where user_id = ? and deleted_at > ?
                """
                + (until == null ? "" : " and deleted_at <= ?")
                + " order by deleted_at asc, id asc"
                + (limit > 0 ? " limit " + limit : "");
        var spec = jdbc.sql(sql).param(userId).param(java.sql.Timestamp.from(since));
        if (until != null) {
            spec = spec.param(java.sql.Timestamp.from(until));
        }
        return spec.query((rs, row) -> new Keyed<>(
                        rs.getTimestamp("deleted_at").toInstant(),
                        new SyncResponse.Deletion(
                                rs.getString("entity_type"),
                                rs.getString("entity_id"))))
                .list();
    }

    private Map<String, Object> deserialise(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            // One unreadable jsonb costs that row its payload, not the page.
            return new LinkedHashMap<>();
        }
    }
}
