package com.weavr.api.save;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.gemini.GeminiClient;
import com.weavr.api.gemini.GeminiProperties;
import com.weavr.api.gemini.GeminiResponse;
import com.weavr.api.job.JobQueue;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryAfterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The model-routing and idempotency logic that sits between a queued save and
 * the Gemini call: skip-if-already-classified, primary-then-fallback on low
 * confidence, and "unusable" landing as a gentle failure rather than success.
 * This is the branching logic flagged as untested after tonight's encoding
 * fix — {@link com.weavr.api.gemini.GeminiClientTest} covers the call itself.
 */
class ClassifySaveHandlerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final GeminiProperties PROPS = new GeminiProperties(
            "test-key", "gemini-2.5-flash-lite", "gemini-2.5-flash",
            500, 20, /* confidenceThreshold */ 0.8, Duration.ofSeconds(30));

    private SaveRepository saves;
    private SaveStageWriter stages;
    private GeminiClient geminiClient;
    private GeminiBudgetService budgetService;
    private JdbcClient jdbc;
    private JobQueue jobQueue;
    private ClassifySaveHandler handler;

    /** stage name -> stored payload; absent means "not written yet". */
    private final Map<String, Map<String, Object>> stageRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        saves = mock(SaveRepository.class);
        stages = mock(SaveStageWriter.class);
        geminiClient = mock(GeminiClient.class);
        budgetService = mock(GeminiBudgetService.class);
        jdbc = mock(JdbcClient.class);
        jobQueue = mock(JobQueue.class);
        stageRows.clear();

        stubStageLookup();
        stubSavesUpdate();

        handler = new ClassifySaveHandler(saves, stages, geminiClient, budgetService, PROPS, jobQueue, jdbc, MAPPER);
    }

    /**
     * Both {@code readStage} call sites run the identical SQL text and differ
     * only in the {@code stage} parameter, so the stub has to track the most
     * recently bound parameters rather than matching on SQL text.
     */
    @SuppressWarnings("unchecked")
    private void stubStageLookup() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        List<Object> lastParams = new ArrayList<>();
        when(jdbc.sql(contains("from save_stages"))).thenReturn(spec);
        when(spec.param(any())).thenAnswer(inv -> {
            lastParams.add(inv.getArgument(0));
            return spec;
        });
        when(spec.query(any(RowMapper.class))).thenAnswer(inv -> {
            String stage = (String) lastParams.get(lastParams.size() - 1);
            JdbcClient.MappedQuerySpec<Map<String, Object>> mapped = mock(JdbcClient.MappedQuerySpec.class);
            when(mapped.optional()).thenReturn(Optional.ofNullable(stageRows.get(stage)));
            return mapped;
        });
    }

    private final List<Object> updateParams = new ArrayList<>();

    private void stubSavesUpdate() {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("structured_data"))).thenReturn(spec);
        when(spec.param(any())).thenAnswer(inv -> {
            updateParams.add(inv.getArgument(0));
            return spec;
        });
        when(spec.update()).thenReturn(1);
    }

    private static JobRecord classifyJob(UUID saveId) {
        return new JobRecord(UUID.randomUUID(), JobType.CLASSIFY_SAVE,
                Map.of("saveId", saveId.toString()), 0, 5, "user-1");
    }

    private static Save textSave(String caption) {
        return Save.accepted(UUID.randomUUID(), SourceType.TEXT, null, caption, null, null);
    }

    /**
     * {@link BudgetApproved}'s constructor is package-private by design — only
     * {@link GeminiBudgetService} can mint one. Since that service is mocked
     * here anyway, a Mockito double stands in rather than weakening the guard.
     */
    private static BudgetApproved budget(String model, int rpd) {
        BudgetApproved approved = mock(BudgetApproved.class);
        when(approved.model()).thenReturn(model);
        when(approved.rpd()).thenReturn(rpd);
        return approved;
    }

    @Test
    void idempotentRetrySkipsGeminiAndReappliesTheStoredResult() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("some text")));
        stageRows.put(ClassifySaveHandler.STAGE_CLASSIFIED, Map.of(
                "knowledgeType", "recipe",
                "confidence", 0.95,
                "modelUsed", "gemini-2.5-flash-lite",
                "title", "Tiramisu"));

        handler.handle(classifyJob(saveId));

        verify(geminiClient, never()).classify(any(), any(), any());
        verify(budgetService, never()).acquire();
        verify(stages, never()).record(any(), any(), any());
        // applyResult still runs, from the stored stage payload.
        assertThat(updateParams).contains("ready", "recipe", 0.95, "gemini-2.5-flash-lite");
    }

    @Test
    void happyPathAppliesTheHighConfidencePrimaryResult() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("3 eggs, mascarpone")));
        BudgetApproved primary = budget("gemini-2.5-flash-lite", 500);
        when(budgetService.acquire()).thenReturn(primary);
        when(geminiClient.classify(eq(saveId), any(), eq(primary))).thenReturn(
                new GeminiResponse("recipe", 0.95, Map.of("title", "Tiramisu"), 100, 40, "gemini-2.5-flash-lite"));

        handler.handle(classifyJob(saveId));

        verify(geminiClient, times(1)).classify(any(), any(), any());
        verify(budgetService, never()).acquireFor(any(), anyInt());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(stages).record(eq(saveId), eq(ClassifySaveHandler.STAGE_CLASSIFIED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("knowledgeType", "recipe")
                .containsEntry("confidence", 0.95)
                .containsEntry("modelUsed", "gemini-2.5-flash-lite")
                .containsEntry("title", "Tiramisu");
        assertThat(updateParams).contains("ready");
    }

    @Test
    void lowConfidenceEscalatesToFallbackAndUsesItWhenMoreConfident() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("blurry overlay text")));
        BudgetApproved primary = budget("gemini-2.5-flash-lite", 500);
        BudgetApproved fallback = budget("gemini-2.5-flash", 20);
        when(budgetService.acquire()).thenReturn(primary);
        when(budgetService.acquireFor("gemini-2.5-flash", 20)).thenReturn(fallback);
        when(geminiClient.classify(eq(saveId), any(), eq(primary))).thenReturn(
                new GeminiResponse("recipe", 0.5, Map.of("title", "guess"), 100, 40, "gemini-2.5-flash-lite"));
        when(geminiClient.classify(eq(saveId), any(), eq(fallback))).thenReturn(
                new GeminiResponse("recipe", 0.9, Map.of("title", "Tiramisu"), 200, 80, "gemini-2.5-flash"));

        handler.handle(classifyJob(saveId));

        verify(geminiClient, times(2)).classify(any(), any(), any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(stages).record(eq(saveId), eq(ClassifySaveHandler.STAGE_CLASSIFIED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("confidence", 0.9)
                .containsEntry("modelUsed", "gemini-2.5-flash")
                .containsEntry("title", "Tiramisu");
    }

    @Test
    void lowConfidenceKeepsThePrimaryResultWhenFallbackBudgetIsExhausted() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("blurry overlay text")));
        BudgetApproved primary = budget("gemini-2.5-flash-lite", 500);
        when(budgetService.acquire()).thenReturn(primary);
        when(budgetService.acquireFor("gemini-2.5-flash", 20))
                .thenThrow(new RetryAfterException("fallback pool exhausted", Duration.ofHours(1)));
        when(geminiClient.classify(eq(saveId), any(), eq(primary))).thenReturn(
                new GeminiResponse("recipe", 0.5, Map.of("title", "best guess"), 100, 40, "gemini-2.5-flash-lite"));

        handler.handle(classifyJob(saveId));

        // The fallback attempt fails but must not propagate — the primary
        // result still gets applied instead of failing the whole save.
        verify(geminiClient, times(1)).classify(any(), any(), any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(stages).record(eq(saveId), eq(ClassifySaveHandler.STAGE_CLASSIFIED), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("confidence", 0.5)
                .containsEntry("modelUsed", "gemini-2.5-flash-lite");
    }

    @Test
    void unusableKnowledgeTypeMarksTheSaveFailedNotReady() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("404 Not Found")));
        BudgetApproved primary = budget("gemini-2.5-flash-lite", 500);
        when(budgetService.acquire()).thenReturn(primary);
        when(geminiClient.classify(eq(saveId), any(), eq(primary))).thenReturn(
                new GeminiResponse("unusable", 1.0, Map.of("reason", "404 page"), 20, 10, "gemini-2.5-flash-lite"));

        handler.handle(classifyJob(saveId));

        assertThat(updateParams).contains("failed", "unusable");
        assertThat(updateParams).doesNotContain("ready");
    }

    @Test
    void blankTextSaveThrowsPermanentException() {
        UUID saveId = UUID.randomUUID();
        when(saves.findById(saveId)).thenReturn(Optional.of(textSave("   ")));

        assertThatThrownBy(() -> handler.handle(classifyJob(saveId)))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_text");
        verify(budgetService, never()).acquire();
    }

    @Test
    void urlSaveWithoutExtractionThrowsPermanentException() {
        UUID saveId = UUID.randomUUID();
        Save urlSave = Save.accepted(UUID.randomUUID(), SourceType.URL, "https://example.com/reel", null, null, null);
        when(saves.findById(saveId)).thenReturn(Optional.of(urlSave));
        // No STAGE_EXTRACTED row in stageRows — extraction hasn't run yet.

        assertThatThrownBy(() -> handler.handle(classifyJob(saveId)))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("no_extraction");
        verify(budgetService, never()).acquire();
    }
}
