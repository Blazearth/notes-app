package com.weavr.api.save;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaveRepository extends JpaRepository<Save, UUID> {

    /** Served by {@code saves_user_created_idx}. */
    List<Save> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Ownership is enforced by the query, not by a check after loading — the
     * service layer is the access-control boundary, so it must never fetch a
     * row it is not allowed to see.
     */
    Optional<Save> findByIdAndUserId(UUID id, UUID userId);

    /** Backs the idempotency check on {@code POST /v1/saves}. */
    Optional<Save> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
