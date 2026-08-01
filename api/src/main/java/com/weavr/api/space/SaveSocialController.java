package com.weavr.api.space;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
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

    SaveSocialController(SaveSocialService social) {
        this.social = social;
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

    @PostMapping("/v1/saves/{id}/comments")
    ResponseEntity<SaveSocialService.Comment> comment(@CurrentUser UUID userId,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.ok(social.addComment(userId, id, request.body()));
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
    @PutMapping("/v1/saves/{id}/vote")
    Map<String, Integer> vote(@CurrentUser UUID userId, @PathVariable UUID id,
                              @RequestBody VoteRequest request) {
        return Map.of("score", social.vote(userId, id, request.value()));
    }
}
