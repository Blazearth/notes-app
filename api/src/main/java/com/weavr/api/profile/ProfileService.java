package com.weavr.api.profile;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.weavr.api.common.BadRequestException;
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * 3–20 chars. Letters, digits, underscores only. No leading/trailing underscore.
     * Examples of valid: BlazeArth, blaze_42, x99
     */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$|^[a-zA-Z0-9]{3}$");

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

    /**
     * Sets the username for a user. Enforces format and uniqueness.
     *
     * <p>Idempotent: if the same user submits the same username again, it is a no-op.
     *
     * @throws BadRequestException if the format is invalid or the username is taken.
     */
    @Transactional
    public void setUsername(UUID userId, String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BadRequestException(
                    "Username must be 3–20 characters and contain only letters, digits, and underscores.");
        }

        ensureExists(userId);

        try {
            jdbc.sql("update profiles set username = ? where id = ?")
                    .params(username, userId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new BadRequestException("Username '" + username + "' is already taken.");
        }
    }

    /**
     * Returns the username for a user, or {@link Optional#empty()} if not set.
     */
    public Optional<String> getUsername(UUID userId) {
        return jdbc.sql("select username from profiles where id = ?")
                .param(userId)
                .query(String.class)
                .optional();
    }
}
