package com.weavr.api.space;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.collection.CollectionEntity;
import com.weavr.api.collection.CollectionNode;
import com.weavr.api.collection.CollectionService;
import com.weavr.api.collection.CollectionService.SaveFacts;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.SaveStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A Space's knowledge layer — S1 and S2 of {@code docs/knowledge-spaces.md}.
 *
 * <p>The redesign, stated once: a Space today is a folder of saves with people
 * attached, and collaborators do not care that Aryan saved Reel #17 — they care
 * about the anime the group should watch. This class is the read path that
 * turns the former into the latter, and it builds <b>nothing new</b> to do it:
 *
 * <ul>
 *   <li><b>S1</b> is {@link CollectionService}'s existing derived merge over
 *       the Space's saves instead of one user's ({@link CollectionService#treeOf}
 *       / {@link CollectionService#entitiesOf}), with each source attributed by
 *       {@code addedBy}. No second merge mechanism, no stored tree — a Space's
 *       collection has the personal one's staleness surface <em>plus</em>
 *       membership changes, so the derive-don't-store argument is stronger
 *       here, not weaker.</li>
 *   <li><b>S2</b> is two batched reads over tables that already exist:
 *       {@code entity_states} joined to {@code space_members} for everyone's
 *       progress, and {@code save_comments} scoped to the Space's saves for the
 *       discussion block. Zero schema, zero AI.</li>
 * </ul>
 *
 * <h2>The disclosure decision (open question 1), made</h2>
 * <p>{@code entity_states} is per {@code (user_id, entity_key)} and
 * <b>global</b>: a member who watched Blue Box from their own library appears
 * as having watched it in any Space whose watchlist contains Blue Box. That is
 * arguably the feature and arguably a leak, and the doc left it open.
 *
 * <p><b>Decided as the doc recommended: global states are shown</b>, and the
 * app states it in the People tab rather than leaving it to be discovered.
 * Sharing progress is what a shared watchlist is <em>for</em>, and the
 * alternative that was rejected — forking state per Space — fragments the
 * single fact the entity layer exists to keep whole ("watched here but not
 * there" is not a thing anyone means). If it proves wrong in use, the escape
 * hatch is a {@code shareStates} flag on {@code space_members} filtering
 * {@link #memberStates}: one column, one {@code where} clause, and every
 * consumer below keeps working.
 *
 * <h2>Whose "done" a Space node counts</h2>
 * <p>{@link CollectionNode#doneCount} means "the caller's" everywhere else. On
 * a Space tree it means <b>anyone's</b> — "3 of the 7 have been watched" is the
 * group fact the Overview is asking for, and a viewer-scoped count under a
 * group heading reads as a claim about the group (which is exactly why S0
 * refused to show one at all). The per-member breakdown that number hides is
 * returned beside it, never instead of it.
 */
@Service
public class SpaceKnowledgeService {

    /**
     * How many comments the Overview's discussion block carries. Small on
     * purpose: this is a "what is being talked about" glance with a tap through
     * to the save, not a message list — the Space has no chat, and a block that
     * looked like one would promise threading nothing here implements.
     */
    private static final int DEFAULT_COMMENT_LIMIT = 8;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final SpaceService spaces;
    private final SaveRepository saves;
    private final ObjectMapper objectMapper;
    private final EntityCommentService comments;
    private final SpacePinService pins;

    SpaceKnowledgeService(JdbcClient jdbc, SpaceService spaces, SaveRepository saves, ObjectMapper objectMapper,
                          EntityCommentService comments, SpacePinService pins) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.saves = saves;
        this.objectMapper = objectMapper;
        this.comments = comments;
        this.pins = pins;
    }

    // --------------------------------------------------------------- shapes

    /**
     * One member's state for one entity.
     *
     * @param state the whole jsonb, not a flag — K7's {@code status}
     *              ("want"/"watching"/"watched") and a {@code rating} both ride
     *              here, and a client that only knows {@code done} still reads
     *              correctly because {@code done} stayed canonical.
     */
    public record MemberState(UUID userId, String displayName, Map<String, Object> state) {}

    /**
     * "Maya: 5 of 12 watched" — one row per member who has touched anything.
     *
     * @param doneCount       entities in the Space whose state this member has
     *                        marked {@code done}
     * @param inProgressCount entities they have a state for that is <em>not</em>
     *                        done — K7's "Watching", and the reason this is two
     *                        numbers rather than one: a watchlist's interesting
     *                        middle state would otherwise be invisible
     */
    public record MemberProgress(UUID userId, String displayName, int doneCount, int inProgressCount) {}

    /**
     * One remark in the Space, from either place discussion can attach.
     *
     * <p>S2 had only the first: a comment on one of the Space's <em>saves</em>.
     * S3 added the second — a comment on a merged <em>entity</em>, which is
     * where "Blue Box starts slow" actually belongs. Exactly one of
     * {@code saveId} and {@code entityKey} is set on any given row.
     *
     * <p><b>One block, not two.</b> "What is being talked about in here" is a
     * single question, and splitting the Overview by which table a remark landed
     * in would ask the reader to care about a distinction that exists for
     * storage reasons. The client renders the subject line from whichever of the
     * two titles is present.
     */
    public record SpaceComment(UUID id, UUID saveId, String saveTitle, String entityKey, String entityName,
                               UUID userId, String displayName, String body, Instant createdAt) {}

    /**
     * What the Overview tab reads in one request.
     *
     * <p>The collection tree is <em>also</em> derived on the client from saves
     * it already holds (S0), so this endpoint is not what makes the Overview
     * render — it is what the client cannot derive: other people's states,
     * other people's comments, and who added what.
     */
    public record SpaceKnowledgeOverview(
            List<CollectionNode> collections,
            /** Distinct entities across every collection — the merge's own count, not a sum. */
            int entityCount,
            /** Of those, how many at least one member has marked done. */
            int doneCount,
            List<MemberProgress> members,
            List<SpaceComment> recentComments,
            /**
             * S4: what a person in this Space chose to put at the top. Rides
             * along here rather than costing a second request, for the same
             * reason the tree does — the Overview is one screen and should be
             * one call.
             */
            List<SpacePinService.Pin> pins) {}

    /**
     * A merged entity as a Space sees it: {@link CollectionEntity}'s fields,
     * flat, plus everyone else's state.
     *
     * <p>A wrapper rather than two more nullable fields on
     * {@link CollectionEntity}, so the personal shape stays exactly what it
     * was and the Space shape is one object the client destructures once.
     *
     * @param state        the <em>viewer's</em> own state, so the control they
     *                     tap reads from the same place it writes to
     * @param memberStates every member's, the viewer included — the viewer
     *                     appears in both, because filtering them out here
     *                     would make "2 people watched this" quietly mean
     *                     "2 other people"
     * @param commentCount S3's thread length, batched in with the states rather
     *                     than fetched per row — a count is what a list needs
     *                     ("2 comments" on the row), and the thread itself is
     *                     one tap and one request away
     */
    public record SpaceEntity(
            String entityKey,
            String name,
            String kind,
            Map<String, Object> fields,
            List<CollectionEntity.Source> sources,
            int sourceCount,
            Map<String, Object> state,
            List<MemberState> memberStates,
            int commentCount) {}

    // ------------------------------------------------------------------ S1

    /**
     * The Space's collection tree. Membership first, always — a non-member gets
     * {@link com.weavr.api.common.NotFoundException} from
     * {@link SpaceService#requireMember} rather than an empty tree, so this
     * never becomes a way to probe which Spaces exist.
     */
    @Transactional(readOnly = true)
    public List<CollectionNode> collections(UUID userId, UUID spaceId) {
        spaces.requireMember(userId, spaceId);
        List<CollectionNode> tree = CollectionService.treeOf(readySaves(spaceId));
        Set<String> allKeys = new LinkedHashSet<>();
        tree.forEach(node -> allKeys.addAll(node.allEntityKeys()));
        Set<String> done = doneByAnyone(spaceId, allKeys);
        return tree.stream().map(node -> node.withDoneCount(done)).toList();
    }

    /**
     * The merged entity list for one node of that tree, with S2's all-member
     * states joined in.
     *
     * <p>{@code nodeId} is the same path scheme the personal endpoint uses —
     * a bare type ({@code recommendation_list}) or a path into its axis tree
     * ({@code recommendation_list~anime~romance}) — so the client's node ids
     * are interchangeable between the two and nothing has to be re-derived.
     */
    @Transactional(readOnly = true)
    public List<SpaceEntity> entities(UUID userId, UUID spaceId, String nodeId, String facet) {
        spaces.requireMember(userId, spaceId);
        List<CollectionEntity> merged = CollectionService.entitiesOf(readySaves(spaceId), nodeId, facet);
        if (merged.isEmpty()) {
            return List.of();
        }

        List<String> keys = merged.stream().map(CollectionEntity::entityKey).toList();
        Map<String, List<MemberState>> states = memberStates(spaceId, keys);
        // S3, batched exactly like the states beside it: one query for the whole
        // screen. A per-row count query would be N+1 over a set that grows with
        // the Space, which is the same trap `statesForSaves` was written to
        // avoid.
        Map<String, Integer> commentCounts = comments.counts(spaceId, keys);

        return merged.stream()
                .map(entity -> {
                    List<MemberState> forEntity = states.getOrDefault(entity.entityKey(), List.of());
                    Map<String, Object> mine = forEntity.stream()
                            .filter(member -> member.userId().equals(userId))
                            .map(MemberState::state)
                            .findFirst()
                            .orElse(null);
                    return new SpaceEntity(entity.entityKey(), entity.name(), entity.kind(), entity.fields(),
                            entity.sources(), entity.sourceCount(), mine, forEntity,
                            commentCounts.getOrDefault(entity.entityKey(), 0));
                })
                .toList();
    }

    // ------------------------------------------------------------------ S2

    /** Overview v1, in one request: the tree, the group's progress, the discussion. */
    @Transactional(readOnly = true)
    public SpaceKnowledgeOverview overview(UUID userId, UUID spaceId, int commentLimit) {
        spaces.requireMember(userId, spaceId);

        List<SaveFacts> ready = readySaves(spaceId);
        List<CollectionNode> tree = CollectionService.treeOf(ready);
        Set<String> allKeys = new LinkedHashSet<>();
        tree.forEach(node -> allKeys.addAll(node.allEntityKeys()));

        Map<String, List<MemberState>> states = memberStates(spaceId, allKeys);
        Set<String> done = doneKeys(states);

        return new SpaceKnowledgeOverview(
                tree.stream().map(node -> node.withDoneCount(done)).toList(),
                allKeys.size(),
                done.size(),
                rollUp(states),
                discussion(spaceId, commentLimit, ready, tree),
                pins.list(userId, spaceId));
    }

    /**
     * The Overview's discussion block: the latest remarks from both places one
     * can be made, interleaved by time.
     *
     * <p>Read {@code limit} of each and keep the newest {@code limit} of the
     * union — a single {@code union all} query would be tidier to look at and
     * would have to hand-roll the two different join shapes (a save's title
     * comes from {@code structured_data}, an entity's from a merge that does not
     * exist in SQL at all), so the interleave is done in Java where both halves
     * are already typed.
     */
    private List<SpaceComment> discussion(UUID spaceId, int limit, List<SaveFacts> ready,
                                          List<CollectionNode> tree) {
        int bounded = Math.clamp(limit <= 0 ? DEFAULT_COMMENT_LIMIT : limit, 1, 50);
        List<EntityCommentService.EntityComment> onEntities = comments.recent(spaceId, bounded);
        List<SpaceComment> onSaves = recentComments(spaceId, bounded);
        if (onEntities.isEmpty()) {
            return onSaves;
        }

        // Names are resolved only when there is something to name, and only for
        // the keys actually commented on: the merge that produces a display name
        // is a full pass over the Space's saves, and paying for it on every
        // Overview of every Space that has no entity discussion would be a cost
        // with no reader.
        Map<String, String> names = entityNames(ready, tree,
                onEntities.stream().map(EntityCommentService.EntityComment::entityKey).collect(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        List<SpaceComment> merged = new ArrayList<>(onSaves);
        onEntities.forEach(comment -> merged.add(new SpaceComment(
                comment.id(), null, null, comment.entityKey(),
                // Falling back to the key rather than to null: an unresolvable
                // key means every save that mentioned it has left the Space, and
                // "on blue box" still tells the reader what the remark was
                // about where a blank subject tells them nothing.
                names.getOrDefault(comment.entityKey(), readableKey(comment.entityKey())),
                comment.userId(), comment.displayName(), comment.body(), comment.createdAt())));

        return merged.stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .limit(bounded)
                .toList();
    }

    /**
     * Display names for a set of entity keys, by re-merging only the types that
     * actually contain them.
     *
     * <p>A key is {@code "screen:blue box"} — normalised, casefolded, and
     * therefore not what anybody wants to read. The name lives on the merged
     * entity, which is derived, so this is the only place it can come from.
     */
    private Map<String, String> entityNames(List<SaveFacts> ready, List<CollectionNode> tree,
                                            Set<String> wanted) {
        Map<String, String> names = new LinkedHashMap<>();
        for (CollectionNode node : tree) {
            if (names.keySet().containsAll(wanted)) break;
            if (node.allEntityKeys().stream().noneMatch(wanted::contains)) continue;
            CollectionService.entitiesOf(ready, node.id(), null).forEach(entity -> {
                if (wanted.contains(entity.entityKey())) names.put(entity.entityKey(), entity.name());
            });
        }
        return names;
    }

    /** The half of an entity key a person can read: {@code "screen:blue box"} → {@code "blue box"}. */
    private static String readableKey(String entityKey) {
        int colon = entityKey.indexOf(':');
        return colon < 0 || colon == entityKey.length() - 1 ? entityKey : entityKey.substring(colon + 1);
    }

    /**
     * The batched all-member read — the doc's {@code SpaceEntityStates
     * .forSpace}, one query joining {@code space_members} × {@code
     * entity_states} filtered to the derived entity-key set.
     *
     * <p>One query for a whole screen, never one per entity, for the same
     * reason {@code SaveItemStateService.statesForSaves} is batched: N here is
     * the size of a merged collection, which grows with the Space rather than
     * being bounded by anything.
     *
     * <p>Members with no state at all simply do not appear, which is what makes
     * {@link #rollUp} a report on people who have engaged rather than a roster
     * with zeroes beside everyone who has not.
     */
    @Transactional(readOnly = true)
    public Map<String, List<MemberState>> memberStates(UUID spaceId, Collection<String> entityKeys) {
        if (entityKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<MemberState>> byEntity = new LinkedHashMap<>();
        jdbc.sql("""
                        select es.entity_key, es.user_id, es.state::text as state,
                               coalesce(p.username, p.display_name, 'Weavr user') as display_name
                        from entity_states es
                        join space_members m on m.user_id = es.user_id
                        left join profiles p on p.id = es.user_id
                        where m.space_id = ? and es.entity_key = any(?::text[])
                        order by coalesce(p.username, p.display_name), es.user_id
                        """)
                .param(spaceId)
                .param(entityKeys.toArray(String[]::new))
                .query((rs, row) -> new Object[]{
                        rs.getString("entity_key"),
                        new MemberState(
                                rs.getObject("user_id", UUID.class),
                                rs.getString("display_name"),
                                deserialise(rs.getString("state")))
                })
                .list()
                .forEach(row -> byEntity
                        .computeIfAbsent((String) row[0], key -> new ArrayList<>())
                        .add((MemberState) row[1]));
        return byEntity;
    }

    /**
     * Latest discussion across the Space's saves — the doc's "surface what
     * exists" step, one indexed query rather than S3's new table.
     *
     * <p>Joined from {@code saves} by {@code space_id} ({@code
     * saves_space_created_idx}) into {@code save_comments} by {@code save_id}
     * ({@code save_comments_save_idx}), so neither side is a scan. The title is
     * read the same {@code coalesce(title, name)} way {@code SpaceService
     * .activity} and {@code SpaceService}'s own feed read it — {@code place}
     * names its title {@code name}, and a third independent guess at that is
     * how {@code saveTitle()} and {@code search_tsv} both broke before.
     */
    @Transactional(readOnly = true)
    public List<SpaceComment> recentComments(UUID spaceId, int limit) {
        return jdbc.sql("""
                        select c.id, c.save_id, c.user_id, c.body, c.created_at,
                               coalesce(p.username, p.display_name, 'Weavr user') as display_name,
                               coalesce(s.structured_data ->> 'title',
                                        s.structured_data ->> 'name') as save_title
                        from save_comments c
                        join saves s on s.id = c.save_id
                        left join profiles p on p.id = c.user_id
                        where s.space_id = ?
                        order by c.created_at desc
                        limit ?
                        """)
                .param(spaceId)
                .param(Math.clamp(limit <= 0 ? DEFAULT_COMMENT_LIMIT : limit, 1, 50))
                .query((rs, row) -> new SpaceComment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("save_id", UUID.class),
                        rs.getString("save_title"),
                        null,
                        null,
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    // ------------------------------------------------------------ pure core

    /**
     * "Maya: 5 of 12 watched", from the same map the entity list is built from
     * — no second query, and no possibility of the two disagreeing.
     *
     * <p>Static and database-free so it can be tested against a fixture,
     * following {@code ShoppingListService.fold} and the collection merge core.
     * Ordered by engagement (done, then in-progress) so the rollup reads as a
     * leaderboard of participation rather than an arbitrary list of user ids.
     */
    public static List<MemberProgress> rollUp(Map<String, List<MemberState>> statesByEntity) {
        record Tally(String displayName, int done, int inProgress) {}
        Map<UUID, Tally> byMember = new LinkedHashMap<>();
        statesByEntity.forEach((entityKey, members) -> members.forEach(member -> {
            boolean done = isDone(member.state());
            byMember.merge(member.userId(),
                    new Tally(member.displayName(), done ? 1 : 0, done ? 0 : 1),
                    (a, b) -> new Tally(a.displayName() == null ? b.displayName() : a.displayName(),
                            a.done() + b.done(), a.inProgress() + b.inProgress()));
        }));

        return byMember.entrySet().stream()
                .map(entry -> new MemberProgress(entry.getKey(), entry.getValue().displayName(),
                        entry.getValue().done(), entry.getValue().inProgress()))
                .sorted((a, b) -> b.doneCount() != a.doneCount()
                        ? Integer.compare(b.doneCount(), a.doneCount())
                        : Integer.compare(b.inProgressCount(), a.inProgressCount()))
                .toList();
    }

    /**
     * Entities <em>anyone</em> has finished — see the class javadoc on whose
     * "done" a Space node counts.
     */
    public static Set<String> doneKeys(Map<String, List<MemberState>> statesByEntity) {
        Set<String> done = new LinkedHashSet<>();
        statesByEntity.forEach((entityKey, members) -> {
            if (members.stream().anyMatch(member -> isDone(member.state()))) done.add(entityKey);
        });
        return done;
    }

    /**
     * {@code done} stayed canonical when K7 added statuses — every status
     * writes it alongside {@code status} — so reading it alone is correct for
     * both, and for every row written before statuses existed.
     */
    private static boolean isDone(Map<String, Object> state) {
        return state != null && Boolean.TRUE.equals(state.get("done"));
    }

    // ----------------------------------------------------------------- glue

    private Set<String> doneByAnyone(UUID spaceId, Set<String> entityKeys) {
        return doneKeys(memberStates(spaceId, entityKeys));
    }

    /**
     * The Space's finished saves, each carrying its owner so the merge can
     * attribute a source. {@code ready} only, matching the personal path: a
     * still-processing save has no {@code structured_data} to merge and a
     * failed one is not knowledge the group has.
     */
    private List<SaveFacts> readySaves(UUID spaceId) {
        return saves.findBySpaceIdAndStatusOrderByCreatedAtDesc(spaceId, SaveStatus.READY).stream()
                .map(save -> new SaveFacts(save.getId(), save.getKnowledgeType(), save.getStructuredData(),
                        save.getCreatedAt(), save.getUserId()))
                .toList();
    }

    private Map<String, Object> deserialise(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            // A malformed row is one entity's state, not the screen — the same
            // call EntityStateService makes for the same reason.
            return Map.of();
        }
    }
}
