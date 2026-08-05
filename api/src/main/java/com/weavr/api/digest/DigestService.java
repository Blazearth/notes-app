package com.weavr.api.digest;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes {@code digests} — one row per (user, week).
 *
 * <p>The write is a full overwrite on conflict, not an increment. The
 * shopping-list Act taught this the hard way: a running total is correct
 * exactly once, and a re-delivered job (a transient failure, a stale claim)
 * runs it twice. A digest recomputed from the same week's saves is idempotent
 * by construction as long as the write replaces rather than adds.
 */
@Service
public class DigestService {

    public record Digest(String summary, int saveCount, LocalDate weekStart) {
    }

    private final JdbcClient jdbc;

    DigestService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<Digest> find(UUID userId, LocalDate weekStart) {
        return jdbc.sql("""
                        select summary, save_count, week_start
                        from digests
                        where user_id = ? and week_start = ?
                        """)
                .param(userId)
                .param(weekStart)
                .query((rs, row) -> new Digest(
                        rs.getString("summary"),
                        rs.getInt("save_count"),
                        rs.getObject("week_start", LocalDate.class)))
                .optional();
    }

    @Transactional
    public void save(UUID userId, LocalDate weekStart, String summary, int saveCount) {
        jdbc.sql("""
                        insert into digests (user_id, week_start, summary, save_count)
                        values (?, ?, ?, ?)
                        on conflict (user_id, week_start)
                        do update set summary = excluded.summary,
                                      save_count = excluded.save_count,
                                      created_at = now()
                        """)
                .param(userId)
                .param(weekStart)
                .param(summary)
                .param(saveCount)
                .update();
    }
}
