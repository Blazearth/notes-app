package com.weavr.api.save;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.save.dto.CreateSaveRequest;
import com.weavr.api.save.dto.SaveResponse;
import com.weavr.api.save.dto.UpdateFlagsRequest;
import com.weavr.api.save.dto.UpdateLifecycleRequest;
import com.weavr.api.save.dto.UpdateSpaceRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/saves")
class SaveController {

    private final SaveService saveService;

    SaveController(SaveService saveService) {
        this.saveService = saveService;
    }

    /**
     * 202, not 201: the resource exists but is not finished. The client shows a
     * processing card and gets a push notification when the pipeline completes.
     *
     * <p>{@code Idempotency-Key} is optional but load-bearing for the share
     * extension: its background {@code URLSession} retries on the OS's
     * schedule, and without a stable key a retry creates a second save.
     */
    @PostMapping
    ResponseEntity<SaveResponse> create(@CurrentUser UUID userId,
                                        @Valid @RequestBody CreateSaveRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false)
                                        String idempotencyKey) {
        Save save = saveService.create(userId, request, idempotencyKey);
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/saves/" + save.getId()))
                .body(SaveResponse.from(save));
    }

    /**
     * @param lifecycle optional filter, comma-separated
     *                  ({@code ?lifecycle=planned,started}). Backs the app's
     *                  "Continue" rail, which is otherwise the same feed —
     *                  a separate endpoint would have duplicated the mapping
     *                  and the paging for one {@code where} clause.
     */
    @GetMapping
    List<SaveResponse> list(@CurrentUser UUID userId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "25") int size,
                            @RequestParam(required = false) List<LifecycleStatus> lifecycle) {
        List<Save> saves = lifecycle == null || lifecycle.isEmpty()
                ? saveService.listForUser(userId, page, size)
                : saveService.listForUserByLifecycle(userId, lifecycle, page, size);
        return saves.stream().map(SaveResponse::from).toList();
    }

    @GetMapping("/{id}")
    SaveResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return SaveResponse.from(saveService.getForUser(userId, id));
    }

    /**
     * {@code saved → planned → started → completed}, and back again — see
     * {@link SaveService#setLifecycle} for why this is not a state machine.
     */
    @PatchMapping("/{id}/lifecycle")
    SaveResponse setLifecycle(@CurrentUser UUID userId, @PathVariable UUID id,
                              @Valid @RequestBody UpdateLifecycleRequest request) {
        return SaveResponse.from(
                saveService.setLifecycle(userId, id, request.lifecycleStatus()));
    }

    /**
     * Backs the Library's swipe-to-favorite and swipe-to-archive actions.
     * Either field may be omitted to leave it as-is.
     */
    @PatchMapping("/{id}/flags")
    SaveResponse setFlags(@CurrentUser UUID userId, @PathVariable UUID id,
                          @RequestBody UpdateFlagsRequest request) {
        return SaveResponse.from(
                saveService.setFlags(userId, id, request.favorite(), request.archived()));
    }

    /**
     * Moves a save into a Space or back to the user's private feed.
     * {@code spaceId: null} removes it from its current Space.
     */
    @PatchMapping("/{id}/space")
    SaveResponse setSpace(@CurrentUser UUID userId, @PathVariable UUID id,
                          @RequestBody UpdateSpaceRequest request) {
        return SaveResponse.from(
                saveService.setSpace(userId, id, request.spaceId()));
    }
}
