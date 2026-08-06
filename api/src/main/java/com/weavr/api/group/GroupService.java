package com.weavr.api.group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.weavr.api.save.Save;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.SaveStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group tree, derived from what the pipeline already extracted.
 *
 * <p><b>No table, no migration, and no extra model call.</b> Groups are a
 * <em>view</em> over {@code saves}, recomputed per request. Three consequences,
 * all of them wanted:
 *
 * <ul>
 *   <li>They cannot go stale. A persisted tree needs invalidating every time a
 *       save is classified, reclassified, enriched or deleted, and the failure
 *       mode of missing one is a folder that lies about its contents.</li>
 *   <li>They cost nothing against the 500 RPD ceiling. The grouping signal —
 *       {@code knowledge_type}, {@code cuisine}, {@code genre}, {@code tags} —
 *       was already produced by the single classify call each save gets. This is
 *       genuinely AI-derived organisation with no AI request of its own, which
 *       is the only kind this app can afford per-user.</li>
 *   <li>Adding a facet level is a change to {@link #FACETS} and nothing
 *       else.</li>
 * </ul>
 *
 * <p>The tradeoff is a full read of the user's finished saves per request. That
 * is fine at library sizes this app will see for a long time, and when it stops
 * being fine the answer is an aggregate query, not caching a tree that would
 * then need invalidating.
 */
@Service
public class GroupService {

    /**
     * Which extracted field subdivides each knowledge type.
     *
     * <p>Chosen because the value is a <em>closed-ish vocabulary the model
     * already emits</em> — cuisines and genres repeat across saves, so they
     * cluster. `category` is now the primary facet for article/product/other/workout
     * since it uses a controlled vocabulary (10-15 values), preventing the
     * tag-per-save explosion that free-text `tags` caused.
     */
    private static final Map<String, String> FACETS = Map.of(
            "recipe", "cuisine",
            "restaurant", "cuisine",
            "movie", "genre",
            "book", "genre",
            "place", "cuisine",
            "article", "category",
            "product", "category",
            "workout", "category",
            "other", "category");

    /**
     * Product-facing names for the types the registry emits.
     *
     * <p>Anything absent falls back to a title-cased version of the raw type,
     * because {@code knowledge_type} is free text from the model — a type this
     * map has never heard of must still get a sensible folder rather than
     * disappearing from the library.
     */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "recipe", "Recipes",
            "movie", "Watchlist",
            "place", "Places",
            "restaurant", "Restaurants",
            "product", "Shopping",
            "article", "Reading",
            "workout", "Workouts",
            "book", "Books",
            "other", "Other");

    /** Separates the type segment from the facet segment in an id. */
    private static final String ID_SEPARATOR = "~";

    /**
     * Minimum number of saves that must share a facet value before it gets
     * its own subgroup. Values shared by fewer saves fall to the parent's
     * loose list instead — a subgroup of 1 is just noise.
     */
    private static final int MIN_GROUP_SIZE = 5;

    /**
     * The only thing the tree needs from a save.
     *
     * <p>Exists so {@link #buildTree} can be a pure function of plain data
     * rather than of JPA entities — {@code Save} has no setters for
     * {@code knowledgeType} or {@code structuredData} (the classifier writes
     * them over raw JDBC), so a test that wanted to build fixtures would be
     * reduced to reflection. The same reasoning made
     * {@code ShoppingListService.fold} static and database-free.
     */
    public record SaveFacts(UUID id, String knowledgeType, Map<String, Object> structuredData) {}

    private final SaveRepository saves;

    GroupService(SaveRepository saves) {
        this.saves = saves;
    }

    @Transactional(readOnly = true)
    public List<GroupNode> listGroups(UUID userId) {
        return buildTree(saves.findByUserIdAndStatusOrderByCreatedAtDesc(userId, SaveStatus.READY)
                .stream()
                .map(save -> new SaveFacts(save.getId(), save.getKnowledgeType(), save.getStructuredData()))
                .toList());
    }

    /** Pure: no database, no Spring, no clock. Everything interesting is here. */
    public static List<GroupNode> buildTree(List<SaveFacts> ready) {
        // Insertion-ordered so the response is stable between calls: saves come
        // back newest-first, so the most recently added type leads. A HashMap
        // here would reshuffle the whole grid on every refresh.
        Map<String, List<SaveFacts>> byType = new LinkedHashMap<>();
        for (SaveFacts save : ready) {
            String type = normalise(save.knowledgeType());
            // `unusable` is the model reporting it could not extract anything.
            // A folder of failures is not a category the user asked for. Belt
            // and braces today — the classifier marks those `failed`, so they
            // never reach this query — but the two rules live far apart and
            // this one is cheap.
            if (type == null || "unusable".equals(type)) continue;
            byType.computeIfAbsent(type, key -> new ArrayList<>()).add(save);
        }

        List<GroupNode> groups = new ArrayList<>();
        byType.forEach((type, typeSaves) -> groups.add(buildTypeGroup(type, typeSaves)));
        return groups;
    }

    @Transactional(readOnly = true)
    public Optional<GroupNode> getGroup(UUID userId, String groupId) {
        return find(listGroups(userId), groupId);
    }

    /**
     * The saves in a group.
     *
     * @param deep {@code true} collects the whole subtree; {@code false} returns
     *             only what sits loose at this level, which is what a folder
     *             shows beneath its subfolders.
     */
    @Transactional(readOnly = true)
    public Optional<List<UUID>> groupSaveIds(UUID userId, String groupId, boolean deep) {
        return find(listGroups(userId), groupId).map(node -> deep ? collect(node) : node.saveIds());
    }

    // ------------------------------------------------------------------ internals

    private static GroupNode buildTypeGroup(String type, List<SaveFacts> typeSaves) {
        String facetField = FACETS.get(type);

        Map<String, List<SaveFacts>> byFacet = new LinkedHashMap<>();
        // LinkedHashSet: insertion-ordered and deduplicates saves that appear
        // under multiple facet values (e.g. a movie with genre ["thriller","drama"]
        // would otherwise be added to loose twice when neither genre hits MIN_GROUP_SIZE).
        Set<UUID> loose = new LinkedHashSet<>();

        for (SaveFacts save : typeSaves) {
            Collection<String> values = facetField == null
                    ? List.of()
                    : facetValues(save, facetField);
            if (values.isEmpty()) {
                // No facet the model could give us. It belongs to the type, not
                // to an invented "Uncategorised" subgroup — that would be a
                // folder describing our extraction rather than their content.
                loose.add(save.id());
                continue;
            }
            // A save can carry several genres or tags, so it can legitimately
            // appear under more than one subgroup. That is a property of the
            // data, not a bug — which is why the parent's count sums subgroups
            // and can therefore exceed the number of distinct saves. See the
            // note on `itemCount` below.
            for (String value : values) {
                byFacet.computeIfAbsent(value, key -> new ArrayList<>()).add(save);
            }
        }

        List<GroupNode> subgroups = new ArrayList<>();
        byFacet.forEach((value, facetSaves) -> {
            if (facetSaves.size() >= MIN_GROUP_SIZE) {
                subgroups.add(GroupNode.of(
                        type + ID_SEPARATOR + slug(value),
                        titleCase(value),
                        null,
                        List.of(),
                        facetSaves.stream().map(SaveFacts::id).toList()));
            } else {
                // Too few saves share this value — add them to loose so they
                // still appear under the parent type, not in a solo subgroup.
                facetSaves.forEach(s -> loose.add(s.id()));
            }
        });

        return GroupNode.of(type, displayName(type), null, subgroups, List.copyOf(loose));
    }

    /** Pulls a facet as a list, tolerating both a string and an array of them. */
    @SuppressWarnings("unchecked")
    private static Collection<String> facetValues(SaveFacts save, String field) {
        Object raw = save.structuredData().get(field);
        if (raw instanceof String s) {
            return isUsable(s) ? List.of(s.trim()) : List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> out = new ArrayList<>();
            for (Object item : collection) {
                if (item instanceof String s && isUsable(s)) out.add(s.trim());
            }
            return out;
        }
        return List.of();
    }

    /**
     * {@code [unclear]} is the registry's sentinel for "the model could not
     * tell", and grouping by it would create a folder named after our own
     * uncertainty — the same reason V4 stripped it from the search vector.
     */
    private static boolean isUsable(String value) {
        String trimmed = value == null ? "" : value.trim();
        return !trimmed.isEmpty() && !"[unclear]".equalsIgnoreCase(trimmed);
    }

    /** Package-visible so the tree tests can exercise it without a repository. */
    static Optional<GroupNode> find(List<GroupNode> nodes, String id) {
        for (GroupNode node : nodes) {
            if (node.id().equals(id)) return Optional.of(node);
            Optional<GroupNode> nested = find(node.subgroups(), id);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    static List<UUID> collect(GroupNode node) {
        List<UUID> out = new ArrayList<>(node.saveIds());
        node.subgroups().forEach(child -> out.addAll(collect(child)));
        return out;
    }

    private static String normalise(String type) {
        if (type == null || type.isBlank()) return null;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(String type) {
        return DISPLAY_NAMES.getOrDefault(type, titleCase(type));
    }

    /** URL-safe and stable: the same facet value always yields the same id. */
    private static String slug(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static String titleCase(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return trimmed;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }
}
