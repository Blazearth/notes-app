package com.weavr.api.group;

import java.util.List;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.save.SaveService;
import com.weavr.api.save.dto.SaveResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI groups — the library organised by what the pipeline understood.
 *
 * <p>Read-only, because nothing here is stored: the tree is derived per request
 * from each save's {@code knowledge_type} and its extracted facets. There is no
 * create or rename endpoint yet, and adding one means deciding how a
 * user-defined folder coexists with a derived one — a real product question,
 * not an oversight.
 */
@RestController
@RequestMapping("/v1/groups")
class GroupController {

    private final GroupService groups;
    private final SaveService saves;

    GroupController(GroupService groups, SaveService saves) {
        this.groups = groups;
        this.saves = saves;
    }

    @GetMapping
    List<GroupNode> list(@CurrentUser UUID userId) {
        return groups.listGroups(userId);
    }

    /**
     * Ids carry a {@code ~} between the type and facet segments, which is safe
     * unencoded in a path. A 404 here means the id names nothing in
     * <em>this user's</em> tree — groups are derived from their own saves, so
     * there is no cross-user id to leak.
     */
    @GetMapping("/{id}")
    GroupNode get(@CurrentUser UUID userId, @PathVariable String id) {
        return groups.getGroup(userId, id)
                .orElseThrow(() -> new NotFoundException("Group not found"));
    }

    /**
     * @param deep {@code false} (the default) returns only the saves sitting
     *             loose at this level, which is what the folder shows under its
     *             subfolders; {@code true} collects the whole subtree.
     */
    @GetMapping("/{id}/saves")
    List<SaveResponse> saves(@CurrentUser UUID userId,
                             @PathVariable String id,
                             @RequestParam(defaultValue = "false") boolean deep) {
        List<UUID> ids = groups.groupSaveIds(userId, id, deep)
                .orElseThrow(() -> new NotFoundException("Group not found"));
        return saves.getAllByIds(userId, ids);
    }
}
