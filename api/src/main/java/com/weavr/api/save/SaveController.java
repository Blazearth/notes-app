package com.weavr.api.save;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.save.dto.CreateSaveRequest;
import com.weavr.api.save.dto.SaveResponse;
import com.weavr.api.save.dto.UpdateFlagsRequest;
import com.weavr.api.save.dto.UpdateItemStateRequest;
import com.weavr.api.save.dto.UpdateLifecycleRequest;
import com.weavr.api.save.dto.UpdateNoteRequest;
import com.weavr.api.save.dto.UpdateSpaceRequest;
import com.weavr.api.search.SearchService;
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

    private static final int MAX_RELATED_LIMIT = 25;

    private final SaveService saveService;
    private final SaveItemStateService itemStates;
    private final SaveRepository saveRepository;
    private final SearchService searchService;

    SaveController(SaveService saveService, SaveItemStateService itemStates,
                   SaveRepository saveRepository, SearchService searchService) {
        this.saveService = saveService;
        this.itemStates = itemStates;
        this.saveRepository = saveRepository;
        this.searchService = searchService;
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
        // Batched: one query for the whole page's item states rather than one
        // per row, so the "continue where I left off" progress a course or
        // checklist needs doesn't turn a feed page into N+1 queries.
        Map<UUID, Map<String, Map<String, Object>>> states =
                itemStates.statesForSaves(userId, saves.stream().map(Save::getId).toList());
        return saves.stream().map(save -> SaveResponse.from(save, states.get(save.getId()))).toList();
    }

    @GetMapping("/{id}")
    SaveResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        Save save = saveService.getForUser(userId, id);
        return SaveResponse.from(save, itemStates.statesFor(userId, save.getId()));
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

    /**
     * Edits the title and body of a {@code TEXT} save.
     * Returns 404 for a non-existent or foreign save, 400 if the target is
     * not a text note.
     */
    @PatchMapping("/{id}/note")
    SaveResponse updateNote(@CurrentUser UUID userId, @PathVariable UUID id,
                            @Valid @RequestBody UpdateNoteRequest request) {
        return SaveResponse.from(
                saveService.updateNote(userId, id, request.title(), request.body()));
    }

    /**
     * The one endpoint behind every knowledge type's object behavior —
     * exercise ticks, checklist items, watch status + rating, "continue where
     * I left off" — see {@link SaveItemStateService}. Returns the full save
     * (echoed, same shape as {@link #get}) rather than just the one item's
     * state, so the client can apply the response the same way it applies any
     * other save mutation.
     */
    @PatchMapping("/{id}/item-state")
    SaveResponse setItemState(@CurrentUser UUID userId, @PathVariable UUID id,
                              @Valid @RequestBody UpdateItemStateRequest request) {
        Save save = saveService.getForUser(userId, id);
        Map<String, Map<String, Object>> states =
                itemStates.setState(userId, id, request.itemPath(), request.state());
        return SaveResponse.from(save, states);
    }

    /**
     * "You also saved…" — Phase 5 §5.3. Costs no Gemini request: it is a
     * pgvector nearest-neighbour query over embeddings every {@code ready}
     * save already has from the ordinary pipeline. {@code getForUser} both
     * authorizes (own save, or a save in a Space the caller belongs to) and
     * 404s a foreign one before the related query ever runs.
     */
    @GetMapping("/{id}/related")
    List<SaveResponse> related(@CurrentUser UUID userId, @PathVariable UUID id,
                               @RequestParam(defaultValue = "10") int limit) {
        saveService.getForUser(userId, id);

        List<UUID> ids = searchService.relatedTo(userId, id, Math.clamp(limit, 1, MAX_RELATED_LIMIT));
        if (ids.isEmpty()) {
            return List.of();
        }

        // Same "one query, re-impose rank order" shape as SearchController —
        // findAllById does not promise to preserve the id list's order.
        Map<UUID, Save> byId = new LinkedHashMap<>();
        saveRepository.findAllById(ids).forEach(save -> byId.put(save.getId(), save));

        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(SaveResponse::from)
                .toList();
    }
}
