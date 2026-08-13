package com.weavr.api.save;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaveRepository extends JpaRepository<Save, UUID> {

    /** Served by {@code saves_user_created_idx}. */
    List<Save> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** A Space's feed. Served by {@code saves_space_created_idx}. */
    List<Save> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId, Pageable pageable);

    /**
     * Ownership is enforced by the query, not by a check after loading — the
     * service layer is the access-control boundary, so it must never fetch a
     * row it is not allowed to see.
     */
    Optional<Save> findByIdAndUserId(UUID id, UUID userId);

    /** Backs the idempotency check on {@code POST /v1/saves}. */
    Optional<Save> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /**
     * Backs the "Continue" rail: what the user has started but not finished.
     *
     * <p>Ordered by {@code updated_at} rather than {@code created_at} — the rail
     * is about what you last touched, not what you saved longest ago, and a
     * lifecycle change is exactly a touch.
     */
    List<Save> findByUserIdAndLifecycleStatusInOrderByUpdatedAtDesc(
            UUID userId, Collection<LifecycleStatus> lifecycleStatuses, Pageable pageable);

    /**
     * Everything the pipeline has finished, for the group tree.
     *
     * <p>Unpaged on purpose: groups are a shape over the <em>whole</em> library,
     * and a count computed from the first page would be wrong in a way nobody
     * could see. That is affordable while a library is thousands of rows and
     * stops being affordable well before it is millions — at which point this
     * becomes an aggregate query, not a bigger fetch.
     *
     * <p>Only {@code ready} saves: an unclassified save has no
     * {@code knowledge_type} to group by, and showing it as "Other" would
     * misreport work still in progress as a finished category.
     */
    List<Save> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, SaveStatus status);

    /**
     * The same shape one scope over: a whole Space's finished saves, for the
     * Space-scoped collection merge (S1, {@code docs/knowledge-spaces.md}).
     * Served by {@code saves_space_created_idx}.
     *
     * <p>Unpaged for the same reason as the personal query above — a Space's
     * knowledge is a shape over <em>all</em> of its saves, and a count derived
     * from the first page would be wrong in a way nobody could see.
     *
     * <p>No {@code userId}: this is the one read here that deliberately crosses
     * users, because merging several members' saves is the entire feature.
     * Membership is established by {@code SpaceKnowledgeService} before this is
     * called — every save with this {@code space_id} is visible to every member
     * by definition.
     */
    List<Save> findBySpaceIdAndStatusOrderByCreatedAtDesc(UUID spaceId, SaveStatus status);

    /**
     * The delta window for {@code GET /v1/sync}, ascending so the last row is
     * the page's cursor. Served by {@code saves_user_updated_idx} (V15) — V1's
     * index is on {@code created_at} and cannot serve this.
     *
     * <p>Strictly {@code >}, because the caller's cursor is a timestamp the
     * server itself handed out and a row exactly at it has already been
     * delivered. One extra row past the page size is fetched to detect overflow
     * — see {@link com.weavr.api.sync.SyncWindow}.
     */
    List<Save> findByUserIdAndUpdatedAtGreaterThanOrderByUpdatedAtAsc(
            UUID userId, Instant since, Pageable pageable);

    /**
     * The same window, closed on a whole-timestamp boundary and unpaged.
     *
     * <p>Unpaged deliberately: the bound is inclusive so that every row sharing
     * the boundary timestamp is delivered together, and a limit here would put
     * back exactly the mid-group split the boundary exists to avoid.
     */
    List<Save> findByUserIdAndUpdatedAtGreaterThanAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
            UUID userId, Instant since, Instant until);

    /**
     * A specific set of the caller's saves, for the group tree's item lists.
     *
     * <p>Scoped by {@code userId} in the query rather than filtered afterwards,
     * so an id belonging to somebody else returns nothing instead of returning
     * a row the service then has to remember to drop.
     */
    List<Save> findByIdInAndUserId(Collection<UUID> ids, UUID userId);

    /**
     * Every save this account owns, any status, any Space — account deletion's
     * sweep. Unpaged and unfiltered for the same reason the group tree's own
     * unpaged queries are: this has to be the whole set, not a page of it, or
     * a save past the first page would silently survive its owner's account.
     */
    List<Save> findByUserId(UUID userId);
}
