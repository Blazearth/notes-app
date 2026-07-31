package com.weavr.api.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.save.Save;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.dto.SaveResponse;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/saves/search?q=…}
 *
 * <p>Deliberately under {@code /v1/saves} rather than a top-level
 * {@code /v1/search}: it returns saves, in the same shape the feed does, so the
 * client can render results with the existing card components and nothing new.
 */
@RestController
@RequestMapping("/v1/saves")
@Validated
class SearchController {

    private static final int MAX_LIMIT = 50;

    private final SearchService searchService;
    private final SaveRepository saves;

    SearchController(SearchService searchService, SaveRepository saves) {
        this.searchService = searchService;
        this.saves = saves;
    }

    /**
     * @param match {@code "both"} when full-text and semantic search both found
     *              this save, otherwise which half did. Exposed because the
     *              escalation-rate lesson applies here too — without it, "is the
     *              vector half contributing anything?" is unanswerable outside
     *              a database session
     */
    record SearchHit(SaveResponse save, String match) {
    }

    @GetMapping("/search")
    List<SearchHit> search(@CurrentUser UUID userId,
                           @RequestParam("q") @Size(min = 1, max = 500) String q,
                           @RequestParam(defaultValue = "25") int limit) {

        List<SearchService.Hit> hits =
                searchService.search(userId, q, Math.clamp(limit, 1, MAX_LIMIT));
        if (hits.isEmpty()) {
            return List.of();
        }

        // One query for the rows, then re-imposed ranking. Fetching each save
        // individually in rank order would be N+1 round trips to a pooled
        // connection for a result set the user is going to see all at once.
        Map<UUID, Save> byId = new LinkedHashMap<>();
        saves.findAllById(hits.stream().map(SearchService.Hit::id).toList())
                .forEach(save -> byId.put(save.getId(), save));

        return hits.stream()
                .map(hit -> {
                    Save save = byId.get(hit.id());
                    // Deleted between the search and the fetch. Rare, but a
                    // null here would 500 an otherwise good result set.
                    return save == null ? null : new SearchHit(SaveResponse.from(save), label(hit));
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String label(SearchService.Hit hit) {
        if (hit.matchedBoth()) {
            return "both";
        }
        return hit.fullTextRank() > 0 ? "text" : "semantic";
    }
}
