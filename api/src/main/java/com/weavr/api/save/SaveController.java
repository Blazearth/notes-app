package com.weavr.api.save;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import com.weavr.api.save.dto.CreateSaveRequest;
import com.weavr.api.save.dto.SaveResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     */
    @PostMapping
    ResponseEntity<SaveResponse> create(@CurrentUser UUID userId,
                                        @Valid @RequestBody CreateSaveRequest request) {
        Save save = saveService.create(userId, request);
        return ResponseEntity
                .accepted()
                .location(URI.create("/v1/saves/" + save.getId()))
                .body(SaveResponse.from(save));
    }

    @GetMapping
    List<SaveResponse> list(@CurrentUser UUID userId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "25") int size) {
        return saveService.listForUser(userId, page, size).stream()
                .map(SaveResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    SaveResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return SaveResponse.from(saveService.getForUser(userId, id));
    }
}
