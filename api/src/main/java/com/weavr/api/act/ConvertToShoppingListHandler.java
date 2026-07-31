package com.weavr.api.act;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.gemini.BudgetApproved;
import com.weavr.api.gemini.GeminiBudgetService;
import com.weavr.api.job.JobHandler;
import com.weavr.api.job.JobRecord;
import com.weavr.api.job.JobType;
import com.weavr.api.job.PermanentJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Act: a saved recipe becomes lines on the shopping list.
 *
 * <p>A job rather than inline work in the request, for the same reason every
 * other model call is one — it spends a Gemini request, so it has to be able to
 * park when the daily budget is gone rather than failing the user's tap.
 * {@link GeminiBudgetService} throws {@code RetryAfterException} in that case,
 * which reschedules without consuming an attempt.
 */
@Component
class ConvertToShoppingListHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ConvertToShoppingListHandler.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ShoppingListConverter converter;
    private final ShoppingListService lists;
    private final GeminiBudgetService budget;
    private final ObjectMapper objectMapper;

    ConvertToShoppingListHandler(JdbcClient jdbc, ShoppingListConverter converter,
                                 ShoppingListService lists, GeminiBudgetService budget,
                                 ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.converter = converter;
        this.lists = lists;
        this.budget = budget;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return JobType.CONVERT_TO_SHOPPING_LIST;
    }

    private record RecipeRow(UUID userId, String knowledgeType, String structuredData) {
    }

    @Override
    public void handle(JobRecord job) {
        UUID saveId = job.uuidParam("saveId");

        RecipeRow recipe = jdbc.sql("""
                        select user_id, knowledge_type, structured_data::text as structured_data
                        from saves
                        where id = ? and status = 'ready'
                        """)
                .param(saveId)
                .query((rs, row) -> new RecipeRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("knowledge_type"),
                        rs.getString("structured_data")))
                .optional()
                .orElseThrow(() -> new PermanentJobException(
                        "save_not_ready", "That save is not ready to convert yet."));

        if (!"recipe".equals(recipe.knowledgeType())) {
            throw new PermanentJobException("not_a_recipe",
                    "Only recipes can be turned into a shopping list.");
        }

        List<String> ingredients = ingredientsOf(recipe.structuredData());
        if (ingredients.isEmpty()) {
            throw new PermanentJobException("no_ingredients",
                    "Weavr couldn't find an ingredient list in that recipe.");
        }

        // Spends a request like any other generation call — an Act is not free,
        // and the plan budgets for it explicitly alongside saves and digests.
        BudgetApproved approved = budget.acquire();

        List<ShoppingListConverter.ItemDraft> drafts = converter.convert(
                saveId, title(recipe.structuredData()), ingredients, approved);

        if (drafts.isEmpty()) {
            throw new PermanentJobException("nothing_to_buy",
                    "Weavr couldn't turn that recipe into a shopping list.");
        }

        lists.addFromSave(recipe.userId(), saveId, drafts);
        recordActUsage(recipe.userId());

        log.info("Save {} converted to {} shopping list item(s)", saveId, drafts.size());
    }

    /**
     * Counts the conversion against this week's Act allowance.
     *
     * <p><b>Recorded, not enforced.</b> The free tier is meant to cap Acts at
     * one per week, but the cap only makes sense once there is a paid tier to
     * escape to — and `subscriptions` is written by a RevenueCat webhook that
     * does not exist yet. Enforcing now would cap every user, including paying
     * ones, at one conversion a week. Counting from the start means the cap can
     * be switched on later against real numbers instead of an empty table.
     *
     * <p>The period is the ISO week containing today, so "one per week" has an
     * unambiguous boundary rather than a rolling seven days that resets
     * differently for every user.
     */
    private void recordActUsage(UUID userId) {
        LocalDate weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        try {
            jdbc.sql("""
                            insert into usage_counters (user_id, period_start, acts_used)
                            values (?, ?, 1)
                            on conflict (user_id, period_start)
                            do update set acts_used = usage_counters.acts_used + 1
                            """)
                    .param(userId)
                    .param(weekStart)
                    .update();
        } catch (RuntimeException e) {
            // Metering must never fail a conversion the user already paid a
            // Gemini request for.
            log.warn("Could not record Act usage for {}: {}", userId, e.toString());
        }
    }

    private List<String> ingredientsOf(String structuredDataJson) {
        Map<String, Object> data = parse(structuredDataJson);
        Object raw = data.get("ingredients");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> ingredients = new ArrayList<>();
        for (Object item : list) {
            String text = String.valueOf(item).trim();
            // "[unclear]" is the registry's sentinel for absent information;
            // sending it to the model would produce a shopping list line
            // saying "unclear".
            if (!text.isEmpty() && !"[unclear]".equalsIgnoreCase(text)) {
                ingredients.add(text);
            }
        }
        return ingredients;
    }

    private String title(String structuredDataJson) {
        Object title = parse(structuredDataJson).get("title");
        return title == null ? null : String.valueOf(title);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * The user tapped a button and is waiting for an answer, so a failure has to
     * surface somewhere. There is no per-Act status row to write it to yet, so
     * this logs — the list simply does not gain the recipe, which is visible.
     */
    @Override
    @Transactional
    public void onPermanentFailure(JobRecord job, String errorCode, String userMessage) {
        log.warn("Shopping-list conversion failed for job {} ({}): {}",
                job.id(), errorCode, userMessage);
    }
}
