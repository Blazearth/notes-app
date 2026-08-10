package com.weavr.api.act;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.billing.UsageService;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.common.QuotaExceededException;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobType;
import com.weavr.api.space.SpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shopping list, and the Act that fills it.
 *
 * <p>The conversion is {@code POST /v1/saves/{id}/acts/shopping-list} rather
 * than a verb on the list, because the interesting resource is the save being
 * acted on. When there is a second Act it slots in beside this one without
 * renaming anything.
 */
@RestController
class ShoppingListController {

    private final ShoppingListService lists;
    private final JobQueue jobQueue;
    private final JdbcClient jdbc;
    private final UsageService usage;
    private final SpaceService spaces;

    ShoppingListController(ShoppingListService lists, JobQueue jobQueue, JdbcClient jdbc,
                           UsageService usage, SpaceService spaces) {
        this.lists = lists;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
        this.usage = usage;
        this.spaces = spaces;
    }

    record ItemResponse(UUID id, String name, String quantity, String unit,
                        String category, boolean checked, List<UUID> sources) {
    }

    /**
     * @param categories the aisle order the client should render, so ordering
     *                   stays a server concern and does not have to be
     *                   duplicated in every client that grows one
     */
    record ShoppingListResponse(UUID id, List<ItemResponse> items, List<String> categories) {
    }

    record CheckedRequest(boolean checked) {
    }

    /**
     * 202: the conversion is a queued job that spends a Gemini request, so the
     * list is not updated by the time this returns. Same contract as creating a
     * save, for the same reason.
     */
    @PostMapping("/v1/saves/{id}/acts/shopping-list")
    @Transactional
    ResponseEntity<Map<String, String>> convert(@CurrentUser UUID userId, @PathVariable UUID id) {
        // Visibility and readiness checked here rather than in the handler, so a
        // bad request fails now with a status the caller can act on instead of
        // becoming a job that fails later where nobody is watching.
        //
        // "Visibility", not "ownership", since S4: a Recipe Space's members
        // convert each other's recipes into the Space's shared list, so the rule
        // is the save's own — yours, or in a Space you are in — exactly what
        // `SaveSocialService.requireVisible` allows for commenting on it.
        String knowledgeType = jdbc
                .sql("""
                        select s.knowledge_type
                        from saves s
                        where s.id = ? and s.status = 'ready'
                          and (s.user_id = ?
                               or exists (select 1 from space_members m
                                          where m.space_id = s.space_id and m.user_id = ?))
                        """)
                .param(id)
                .param(userId)
                .param(userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("That save is not ready to convert yet."));

        if (!"recipe".equals(knowledgeType)) {
            throw new NotFoundException("Only recipes can be turned into a shopping list.");
        }

        // 402 here rather than letting the handler discover it: the user is
        // watching, and a paywall they can act on beats a conversion that
        // silently never arrives. The handler re-checks, because this check
        // loses a race between two taps.
        UsageService.Allowance allowance = usage.checkActs(userId);
        if (!allowance.allowed()) {
            throw new QuotaExceededException(allowance.quota(), allowance.limit(), allowance.used(),
                    "You've used your free shopping list this week.");
        }

        // Keyed by save, so a double tap enqueues one conversion. The handler is
        // separately idempotent per (list, save), which covers a retry that
        // lands after the first job already finished. `actorId` rides in the
        // payload because the weekly cap is the caller's, and since S4 the
        // caller need not be the save's owner.
        jobQueue.enqueueForUser(
                JobType.CONVERT_TO_SHOPPING_LIST,
                Map.of("saveId", id.toString(), "actorId", userId.toString()),
                JobType.CONVERT_TO_SHOPPING_LIST + ":" + id,
                userId);

        return ResponseEntity.accepted().body(Map.of("status", "converting"));
    }

    @GetMapping("/v1/shopping-list")
    ShoppingListResponse get(@CurrentUser UUID userId) {
        return respond(lists.get(userId, null));
    }

    /**
     * S4: the Space's own list, which any member reads and any member ticks off.
     *
     * <p>Hung off the Space rather than taking a {@code ?spaceId=} on the
     * personal route, because these are two lists and not two views of one — the
     * mutations below are shared (an item id addresses exactly one row on
     * exactly one list, and the service proves access per statement), but
     * <em>which list</em> is a property of the resource, not a filter on it.
     */
    @GetMapping("/v1/spaces/{spaceId}/shopping-list")
    ShoppingListResponse getForSpace(@CurrentUser UUID userId, @PathVariable UUID spaceId) {
        spaces.requireMember(userId, spaceId);
        return respond(lists.get(userId, spaceId));
    }

    private ShoppingListResponse respond(ShoppingListService.ShoppingList list) {
        return new ShoppingListResponse(
                list.id(),
                list.items().stream()
                        .map(i -> new ItemResponse(i.id(), i.name(), i.quantity(), i.unit(),
                                i.category(), i.checked(), i.sources()))
                        .toList(),
                ShoppingListConverter.CATEGORIES);
    }

    @PatchMapping("/v1/shopping-list/items/{itemId}")
    ResponseEntity<Void> setChecked(@CurrentUser UUID userId, @PathVariable UUID itemId,
                                    @RequestBody CheckedRequest body) {
        lists.setChecked(userId, itemId, body.checked());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/shopping-list/items/{itemId}")
    ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID itemId) {
        lists.deleteItem(userId, itemId);
        return ResponseEntity.noContent().build();
    }

    /** "I've been shopping" — drops everything ticked off, keeps the rest. */
    @DeleteMapping("/v1/shopping-list/checked")
    Map<String, Integer> clearChecked(@CurrentUser UUID userId) {
        return Map.of("removed", lists.clearChecked(userId, null));
    }

    /**
     * The same, on a Space's list. A separate route rather than a parameter for
     * the same reason as the read — and because scoping matters most here: one
     * button that cleared both lists would be unrecoverable.
     */
    @DeleteMapping("/v1/spaces/{spaceId}/shopping-list/checked")
    Map<String, Integer> clearCheckedForSpace(@CurrentUser UUID userId, @PathVariable UUID spaceId) {
        spaces.requireMember(userId, spaceId);
        return Map.of("removed", lists.clearChecked(userId, spaceId));
    }
}
