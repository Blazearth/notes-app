package com.weavr.api.save;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records what each pipeline stage produced, in {@code save_stages}.
 *
 * <p>This is the difference between "the save failed" and knowing *where*. Once
 * the extraction cascade exists, captions, metadata, ASR output and OCR text
 * each land here as their own row, so a bad result can be traced to the stage
 * that produced it without re-running anything.
 *
 * <p>Upserts, because a job that is retried re-runs stages it already completed.
 */
@Component
public class SaveStageWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    SaveStageWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID saveId, String stage, Map<String, Object> payload) {
        jdbc.sql("""
                        insert into save_stages (save_id, stage, payload)
                        values (?, ?, ?::jsonb)
                        on conflict (save_id, stage)
                        do update set payload = excluded.payload, created_at = now()
                        """)
                .param(saveId)
                .param(stage)
                .param(objectMapper.writeValueAsString(payload == null ? Map.of() : payload))
                .update();
    }
}
