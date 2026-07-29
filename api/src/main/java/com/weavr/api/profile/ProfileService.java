package com.weavr.api.profile;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supabase Auth owns {@code auth.users}; we own {@code profiles}. Nothing
 * bridges the two automatically.
 *
 * <p>Rather than a trigger on {@code auth.users} — which would put a migration
 * inside the auth schema — the profile row is created lazily on the user's
 * first authenticated write. That also covers users who signed up before this
 * table existed.
 */
@Service
public class ProfileService {

    private final JdbcClient jdbc;

    ProfileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent. Safe to call on every write path; it is a single indexed
     * upsert against the primary key.
     */
    @Transactional
    public void ensureExists(UUID userId) {
        jdbc.sql("insert into profiles (id) values (?) on conflict (id) do nothing")
                .param(userId)
                .update();
    }
}
