package com.weavr.api.act;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobType;
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

    ShoppingListController(ShoppingListService lists, JobQueue jobQueue, JdbcClient jdbc) {
        this.lists = lists;
        this.jobQueue = jobQueue;
        this.jdbc = jdbc;
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
        // Ownership and readiness checked here rather than in the handler, so a
        // bad request fails now with a status the caller can act on instead of
        // becoming a job that fails later where nobody is watching.
        String knowledgeType = jdbc
                .sql("select knowledge_type from saves where id = ? and user_id = ? and status = 'ready'")
                .param(id)
                .param(userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("That save is not ready to convert yet."));

        if (!"recipe".equals(knowledgeType)) {
            throw new NotFoundException("Only recipes can be turned into a shopping list.");
        }

        // Keyed by save, so a double tap enqueues one conversion. The handler is
        // separately idempotent per (list, save), which covers a retry that
        // lands after the first job already finished.
        jobQueue.enqueueForUser(
                JobType.CONVERT_TO_SHOPPING_LIST,
                Map.of("saveId", id.toString()),
                JobType.CONVERT_TO_SHOPPING_LIST + ":" + id,
                userId);

        return ResponseEntity.accepted().body(Map.of("status", "converting"));
    }

    @GetMapping("/v1/shopping-list")
    ShoppingListResponse get(@CurrentUser UUID userId) {
        ShoppingListService.ShoppingList list = lists.get(userId);
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
        return Map.of("removed", lists.clearChecked(userId));
    }
}
