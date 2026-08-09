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

    SpaceController(SpaceService spaces, SaveService saves, DuplicateDetector duplicates,
                    IdempotencyService idempotency) {
        this.spaces = spaces;
        this.saves = saves;
        this.duplicates = duplicates;
        this.idempotency = idempotency;
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
