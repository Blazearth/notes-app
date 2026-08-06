package com.weavr.api.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.weavr.api.billing.UsageService;
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
    private final UsageService usage;

    ConvertToShoppingListHandler(JdbcClient jdbc, ShoppingListConverter converter,
                                 ShoppingListService lists, GeminiBudgetService budget,
                                 ObjectMapper objectMapper, UsageService usage) {
        this.jdbc = jdbc;
        this.converter = converter;
        this.lists = lists;
        this.budget = budget;
        this.objectMapper = objectMapper;
        this.usage = usage;
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

        // Re-checked here even though ShoppingListController already refused an
        // over-cap request: the controller check is for the user's benefit (an
        // immediate 402 instead of a job that quietly does nothing), this one is
        // for correctness. Two taps racing each other both pass the controller.
        UsageService.Allowance allowance = usage.checkActs(recipe.userId());
        if (!allowance.allowed()) {
            throw new PermanentJobException("quota_exceeded",
                    "You've used your free shopping list this week. Upgrade for unlimited.");
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

        // Counting moved to UsageService when the cap became enforceable —
        // metering and enforcement reading the same period boundary from the
        // same place is the only way they can agree.
        usage.countAct(recipe.userId());

        log.info("Save {} converted to {} shopping list item(s)", saveId, drafts.size());
    }

    private List<String> ingredientsOf(String structuredDataJson) {
        return ingredientLines(parse(structuredDataJson).get("ingredients"));
    }

    /**
     * Ingredients arrive in two shapes and both must work forever: saves
     * classified before 2026-08-07 hold flat strings ("250g mascarpone"),
     * newer ones hold {@code {name, quantity, note}} objects — there is no
     * reprocess path, so the old shape never ages out. Objects are folded
     * back into the line the converter's prompt was written for.
     */
    static List<String> ingredientLines(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> ingredients = new ArrayList<>();
        for (Object item : list) {
            String text = item instanceof Map<?, ?> parts ? lineOf(parts) : clean(item);
            if (text != null) {
                ingredients.add(text);
            }
        }
        return ingredients;
    }

    private static String lineOf(Map<?, ?> parts) {
        String name = clean(parts.get("name"));
        if (name == null) {
            return null;
        }
        String quantity = clean(parts.get("quantity"));
        String note = clean(parts.get("note"));
        return (quantity == null ? "" : quantity + " ") + name + (note == null ? "" : ", " + note);
    }

    /**
     * "[unclear]" is the registry's sentinel for absent information; sending
     * it to the model would produce a shopping list line saying "unclear".
     */
    private static String clean(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "[unclear]".equalsIgnoreCase(text) ? null : text;
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
