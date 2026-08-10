package com.weavr.api.space;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.common.IdempotencyService;
import com.weavr.api.save.SaveService;
import com.weavr.api.save.dto.SaveResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spaces: the collaboration surface, and the growth loop from §3 of the spec —
 * a free user pulled into a Pro user's Space is the cheapest acquisition
 * channel the product has.
 *
 * <p>Every method takes the caller's id from {@code @CurrentUser} and passes it
 * to {@link SpaceService}, which is where authorisation lives. Nothing here
 * decides who may do what; a controller that started making that judgement
 * would be a second place to keep the rules consistent.
 */
@RestController
@RequestMapping("/v1/spaces")
class SpaceController {

    private final SpaceService spaces;
    private final SaveService saves;
    private final DuplicateDetector duplicates;
    private final IdempotencyService idempotency;
    private final SpaceKnowledgeService knowledge;
    private final EntityCommentService entityComments;
    private final SpacePinService pins;

    SpaceController(SpaceService spaces, SaveService saves, DuplicateDetector duplicates,
                    IdempotencyService idempotency, SpaceKnowledgeService knowledge,
                    EntityCommentService entityComments, SpacePinService pins) {
        this.spaces = spaces;
        this.saves = saves;
        this.duplicates = duplicates;
        this.idempotency = idempotency;
        this.knowledge = knowledge;
        this.entityComments = entityComments;
        this.pins = pins;
    }

    record CreateSpaceRequest(
            @NotBlank(message = "name is required")
            @Size(max = 80, message = "name must be 80 characters or fewer")
            String name,
            String type) {
    }

    record RenameSpaceRequest(
            @NotBlank(message = "name is required")
            @Size(max = 80, message = "name must be 80 characters or fewer")
            String name) {
    }

    /**
     * @param expiresInHours null for a link that never expires
     * @param maxUses        null for "anyone with the link"; 1 for a single
     *                       person
     */
    record CreateInviteRequest(SpaceRole role,
                               @Positive(message = "expiresInHours must be positive")
                               Integer expiresInHours,
                               @Positive(message = "maxUses must be positive")
                               Integer maxUses) {
    }

    record SetRoleRequest(SpaceRole role) {
    }

    /**
     * S3. {@code entityKey} is in the body rather than the path for the same
     * reason it is a query parameter on the read — see
     * {@link #entityComments}.
     */
    record EntityCommentRequest(
            @NotBlank(message = "entityKey is required")
            String entityKey,
            @NotBlank(message = "body is required")
            @Size(max = 2000, message = "a comment must be 2000 characters or fewer")
            String body) {
    }

    /**
     * S4.
     *
     * @param kind    {@code save} or {@code collection} — free text against a
     *                vocabulary in {@link SpacePinService}, so a new kind is
     *                never a migration
     * @param subject a save id, or a collection node id
     * @param payload the kind's own detail, replaced wholesale on a repeat pin.
     *                A {@code save} pin's {@code {"date": …}} is how the doc's
     *                cooking schedule exists without a calendar feature.
     */
    record PinRequest(
            String kind,
            @NotBlank(message = "subject is required")
            String subject,
            Map<String, Object> payload) {
    }

    /**
     * {@code Idempotency-Key} is optional and load-bearing for any retrying
     * caller: this mints a new Space every time it is called, so a request whose
     * response was lost and was then retried would leave the user with two
     * identically-named Spaces and no way to tell which one anybody joined. See
     * {@link IdempotencyService}.
     */
    @PostMapping
    ResponseEntity<SpaceService.Space> create(@CurrentUser UUID userId,
                                              @Valid @RequestBody CreateSpaceRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false)
                                              String idempotencyKey) {
        SpaceService.Space space = idempotency.execute(
                userId, idempotencyKey, "POST /v1/spaces", SpaceService.Space.class,
                () -> spaces.create(userId, request.name(), request.type()));
        return ResponseEntity.created(URI.create("/v1/spaces/" + space.id())).body(space);
    }

    @GetMapping
    List<SpaceService.Space> list(@CurrentUser UUID userId) {
        return spaces.listForUser(userId);
    }

    @GetMapping("/{id}")
    SpaceService.Space get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return spaces.get(userId, id);
    }

    @PatchMapping("/{id}")
    SpaceService.Space rename(@CurrentUser UUID userId, @PathVariable UUID id,
                              @Valid @RequestBody RenameSpaceRequest request) {
        return spaces.rename(userId, id, request.name());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID id) {
        spaces.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** The Space's feed. Ordinary save shapes, so the client reuses its cards. */
    @GetMapping("/{id}/saves")
    List<SaveResponse> saves(@CurrentUser UUID userId, @PathVariable UUID id,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        spaces.requireMember(userId, id);
        return saves.listForSpace(id, page, size).stream().map(SaveResponse::from).toList();
    }

    // ----------------------------------------------------------- knowledge

    /**
     * S1: the Space's collection tree — the same derived merge the personal
     * {@code GET /v1/collections} runs, over the Space's saves instead of the
     * caller's. Node ids follow the identical scheme, so a client can hand one
     * straight to {@link #spaceCollectionEntities} below.
     *
     * <p>{@code doneCount} on these nodes means "entities <em>anyone</em> in
     * the Space has finished", not the caller's own — see
     * {@link SpaceKnowledgeService}'s javadoc.
     */
    @GetMapping("/{id}/collections")
    List<com.weavr.api.collection.CollectionNode> spaceCollections(@CurrentUser UUID userId,
                                                                    @PathVariable UUID id) {
        return knowledge.collections(userId, id);
    }

    /**
     * S1 + S2: one node's merged entities, each source attributed
     * ({@code addedBy}) and each entity carrying every member's state.
     *
     * <p>An unknown node returns an empty list rather than 404, matching
     * {@code CollectionController}: the node id is derived, not stored, so
     * "doesn't exist" and "produced nothing today" are the same answer.
     * Membership, by contrast, is a real 404 — from {@code requireMember}.
     */
    @GetMapping("/{id}/collections/{nodeId}")
    List<SpaceKnowledgeService.SpaceEntity> spaceCollectionEntities(
            @CurrentUser UUID userId, @PathVariable UUID id, @PathVariable String nodeId,
            @RequestParam(required = false) String facet) {
        return knowledge.entities(userId, id, nodeId, facet);
    }

    /**
     * S2: everything the Overview tab needs that the client cannot derive from
     * saves it already holds — other members' progress, the discussion block,
     * and the group-scoped done counts.
     *
     * <p>The collection tree rides along so a first-time visitor gets one
     * request rather than two; a client that has the Space's saves cached
     * derives the same tree locally and offline, and uses this only to overlay
     * what is genuinely other people's.
     */
    @GetMapping("/{id}/knowledge")
    SpaceKnowledgeService.SpaceKnowledgeOverview spaceKnowledge(
            @CurrentUser UUID userId, @PathVariable UUID id,
            @RequestParam(defaultValue = "8") int comments) {
        return knowledge.overview(userId, id, comments);
    }

    // ------------------------------------------------------ entity comments

    /**
     * S3: one merged entity's thread.
     *
     * <p><b>The entity key rides in the query string, never in the path</b>, and
     * that is the same call {@code PATCH /v1/entity-state} and
     * {@code PATCH /v1/saves/{id}/item-state} both made: a key is
     * {@code "screen:blue box"} — a colon and a space — and threading that
     * through a path segment means encoding it correctly in every client
     * forever. A query parameter carries it as-is.
     */
    @GetMapping("/{id}/entity-comments")
    List<EntityCommentService.EntityComment> entityComments(
            @CurrentUser UUID userId, @PathVariable UUID id,
            @RequestParam String entityKey) {
        return entityComments.list(userId, id, entityKey);
    }

    /**
     * {@code Idempotency-Key} for the same reason
     * {@code POST /v1/saves/{id}/comments} takes one: this is a queued write
     * that <em>creates</em> a row rather than setting a value, so a retry after
     * a lost response is the difference between one comment and two identical
     * ones.
     */
    @PostMapping("/{id}/entity-comments")
    EntityCommentService.EntityComment addEntityComment(
            @CurrentUser UUID userId, @PathVariable UUID id,
            @Valid @RequestBody EntityCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotency.execute(
                userId, idempotencyKey, "POST /v1/spaces/{id}/entity-comments",
                EntityCommentService.EntityComment.class,
                () -> entityComments.add(userId, id, request.entityKey(), request.body()));
    }

    @DeleteMapping("/{id}/entity-comments/{commentId}")
    ResponseEntity<Void> deleteEntityComment(@CurrentUser UUID userId, @PathVariable UUID id,
                                             @PathVariable UUID commentId) {
        entityComments.delete(userId, id, commentId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- pins

    /**
     * S4: what someone in this Space chose to put at the top — a save as the
     * "current program", a collection above the others.
     *
     * <p>Also rides along on {@code /knowledge} so the Overview stays one
     * request; this route exists for the surfaces that want pins without the
     * rest of it.
     */
    @GetMapping("/{id}/pins")
    List<SpacePinService.Pin> pins(@CurrentUser UUID userId, @PathVariable UUID id) {
        return pins.list(userId, id);
    }

    /**
     * Pins something, or replaces the payload of a pin that already exists —
     * {@code editor}, because this changes what everyone sees.
     *
     * <p>An upsert rather than a create, so a double tap is one pin. That is
     * also why there is no {@code Idempotency-Key} here: the write is already
     * idempotent on {@code (space, kind, subject)} by construction, which is a
     * stronger guarantee than a replayed response.
     */
    @PostMapping("/{id}/pins")
    SpacePinService.Pin pin(@CurrentUser UUID userId, @PathVariable UUID id,
                            @Valid @RequestBody PinRequest request) {
        return pins.pin(userId, id, request.kind(), request.subject(),
                request.payload() == null ? Map.of() : request.payload());
    }

    @DeleteMapping("/{id}/pins/{pinId}")
    ResponseEntity<Void> unpin(@CurrentUser UUID userId, @PathVariable UUID id,
                               @PathVariable UUID pinId) {
        pins.unpin(userId, id, pinId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- members

    @GetMapping("/{id}/members")
    List<SpaceService.Member> members(@CurrentUser UUID userId, @PathVariable UUID id) {
        return spaces.members(userId, id);
    }

    @PatchMapping("/{id}/members/{memberId}")
    ResponseEntity<Void> setRole(@CurrentUser UUID userId, @PathVariable UUID id,
                                 @PathVariable UUID memberId,
                                 @RequestBody SetRoleRequest request) {
        spaces.setRole(userId, id, memberId, request.role());
        return ResponseEntity.noContent().build();
    }

    /** Removing someone, or leaving — {@code memberId} being your own id is the latter. */
    @DeleteMapping("/{id}/members/{memberId}")
    ResponseEntity<Void> removeMember(@CurrentUser UUID userId, @PathVariable UUID id,
                                      @PathVariable UUID memberId) {
        spaces.removeMember(userId, id, memberId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- invites

    /**
     * The response carries the code rather than a full URL: the deep-link
     * scheme is the client's business, and the QR code is generated on the
     * device from whatever the client decides the link should look like.
     *
     * <p>That is also why {@code Idempotency-Key} matters more here than
     * anywhere: a replay has to return the <em>same code</em>, not merely avoid
     * creating a second invite. A retry that succeeded with an empty body would
     * leave the caller without the one thing the request exists to produce.
     */
    @PostMapping("/{id}/invites")
    SpaceService.Invite createInvite(@CurrentUser UUID userId, @PathVariable UUID id,
                                     @Valid @RequestBody CreateInviteRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false)
                                     String idempotencyKey) {
        SpaceRole role = request.role() == null ? SpaceRole.EDITOR : request.role();
        return idempotency.execute(
                userId, idempotencyKey, "POST /v1/spaces/{id}/invites", SpaceService.Invite.class,
                () -> spaces.createInvite(userId, id, role, request.expiresInHours(), request.maxUses()));
    }

    @GetMapping("/{id}/invites")
    List<SpaceService.Invite> invites(@CurrentUser UUID userId, @PathVariable UUID id) {
        return spaces.listInvites(userId, id);
    }

    @DeleteMapping("/{id}/invites/{inviteId}")
    ResponseEntity<Void> revokeInvite(@CurrentUser UUID userId, @PathVariable UUID id,
                                      @PathVariable UUID inviteId) {
        spaces.revokeInvite(userId, id, inviteId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- joining

    /**
     * Outside {@code /{id}} because the joiner does not know the Space id — the
     * code is all they have, and resolving it is the point.
     */
    @GetMapping("/invites/{code}")
    SpaceService.InvitePreview previewInvite(@CurrentUser UUID userId, @PathVariable String code) {
        return spaces.previewInvite(userId, code);
    }

    @PostMapping("/invites/{code}/accept")
    SpaceService.Space acceptInvite(@CurrentUser UUID userId, @PathVariable String code) {
        return spaces.acceptInvite(userId, code);
    }

    // ----------------------------------------------------------- duplicates

    /**
     * Open merge suggestions. The genuinely differentiating feature: no
     * competitor in the teardown matches two saves of the same restaurant that
     * arrived from different URLs.
     */
    @GetMapping("/{id}/duplicates")
    List<DuplicateDetector.Suggestion> duplicates(@CurrentUser UUID userId, @PathVariable UUID id) {
        return duplicates.suggestions(userId, id);
    }

    /** "These are not the same thing." */
    @PostMapping("/{id}/duplicates/{suggestionId}/dismiss")
    ResponseEntity<Void> dismissDuplicate(@CurrentUser UUID userId, @PathVariable UUID id,
                                          @PathVariable UUID suggestionId) {
        duplicates.dismiss(userId, id, suggestionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * "Yes, these are the same" — the newer save leaves the Space and returns
     * to its owner's private library. Not a delete: see
     * {@link DuplicateDetector#merge}.
     */
    @PostMapping("/{id}/duplicates/{suggestionId}/merge")
    ResponseEntity<Void> mergeDuplicate(@CurrentUser UUID userId, @PathVariable UUID id,
                                        @PathVariable UUID suggestionId) {
        duplicates.merge(userId, id, suggestionId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- activity

    @GetMapping("/{id}/activity")
    Map<String, List<SpaceService.ActivityEntry>> activity(
            @CurrentUser UUID userId, @PathVariable UUID id,
            @RequestParam(defaultValue = "30") int limit) {
        return Map.of("activity", spaces.activity(userId, id, limit));
    }
}
