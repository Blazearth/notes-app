package com.weavr.api.space;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comments and votes on a save.
 *
 * <p>Hung off {@code /v1/saves/{id}} rather than off a Space, because the save
 * is the thing being discussed and it may or may not be in one — a comment on
 * your own private save is a note to self, which is useful and free to allow.
 */
@RestController
class SaveSocialController {

    private final SaveSocialService social;
    private final IdempotencyService idempotency;

    SaveSocialController(SaveSocialService social, IdempotencyService idempotency) {
        this.social = social;
        this.idempotency = idempotency;
    }

    record CommentRequest(
            @NotBlank(message = "body is required")
            @Size(max = 2000, message = "a comment must be 2000 characters or fewer")
            String body) {
    }

    /** @param value 1, -1, or 0 to clear an existing vote */
    record VoteRequest(int value) {
    }

    @GetMapping("/v1/saves/{id}/comments")
    List<SaveSocialService.Comment> comments(@CurrentUser UUID userId, @PathVariable UUID id) {
        return social.comments(userId, id);
    }

    /**
     * {@code Idempotency-Key} is optional here and always sent by the app: this
     * is the one queued write in {@code app/src/local/outbox.ts} that creates a
     * row rather than setting a value, so a retry after a lost response is the
     * difference between one comment and two identical ones.
     */
    @PostMapping("/v1/saves/{id}/comments")
    ResponseEntity<SaveSocialService.Comment> comment(@CurrentUser UUID userId,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody CommentRequest request,
                                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                                      String idempotencyKey) {
        return ResponseEntity.ok(idempotency.execute(
                userId, idempotencyKey, "POST /v1/saves/{id}/comments", SaveSocialService.Comment.class,
                () -> social.addComment(userId, id, request.body())));
    }

    @DeleteMapping("/v1/saves/{id}/comments/{commentId}")
    ResponseEntity<Void> deleteComment(@CurrentUser UUID userId, @PathVariable UUID id,
                                       @PathVariable UUID commentId) {
        social.deleteComment(userId, id, commentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT, not POST: a vote is a value the user sets, and setting it twice is
     * the same as setting it once. That makes a retried request harmless
     * without any dedupe machinery.
     */
    @GetMapping("/v1/saves/{id}/vote")
    SaveSocialService.VoteState voteState(@CurrentUser UUID userId, @PathVariable UUID id) {
        return social.voteState(userId, id);
    }

    @PutMapping("/v1/saves/{id}/vote")
    Map<String, Integer> vote(@CurrentUser UUID userId, @PathVariable UUID id,
                              @RequestBody VoteRequest request) {
        return Map.of("score", social.vote(userId, id, request.value()));
    }
}
