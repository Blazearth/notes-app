package com.weavr.api.act;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.sync.TombstoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * A shopping list: one open list per scope, items merged into it from any number
 * of recipes.
 *
 * <p>Written with {@code JdbcClient} rather than JPA entities. The interesting
 * operation here is an upsert-with-merge whose merge rule lives in Java
 * ({@link Quantities}), and expressing that through a persistence context buys
 * nothing but a lazy-loading question.
 *
 * <h2>Two scopes since V18, and the merge rule did not change</h2>
 * <p>A list belongs either to a user ({@code space_id is null}, as it always
 * has) or to a Space — S4 of {@code docs/knowledge-spaces.md}, because four
 * people planning Saturday need one list, not four. Every method below takes a
 * nullable {@code spaceId} that says which, and <b>{@link #fold} is untouched
 * by the whole feature</b>: a line already stores each contributing recipe's own
 * quantity and recomputes the total, so two <em>people's</em> recipes merge by
 * exactly the rule two of one person's always did. That design was forced by a
 * re-delivered job silently inflating garlic from 7 cloves to 10; it pays for
 * sharing for free.
 *
 * <p><b>Membership is the caller's job, not this class's.</b> Reads and the Act
 * go through {@code SpaceService.requireMember} / {@code requireRole} before
 * they get here. The <em>item</em> mutations are the exception: they take an
 * item id with no scope attached, so each one proves access in its own
 * {@code where} clause rather than loading a row and then judging it — the same
 * rule the personal path already followed, widened by one {@code exists}.
 */
@Service
public class ShoppingListService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingListService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TombstoneService tombstones;

    ShoppingListService(JdbcClient jdbc, ObjectMapper objectMapper, TombstoneService tombstones) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tombstones = tombstones;
    }

    public record Item(UUID id, String name, String quantity, String unit,
                       String category, boolean checked, List<UUID> sources) {
    }

    public record ShoppingList(UUID id, List<Item> items) {
    }

    /**
     * The open list for a scope, created on first use.
     *
     * <p>The insert races with itself when two Act conversions land together —
     * hence {@code on conflict do nothing} against the partial unique index,
     * then a re-read. Same shape as the save idempotency race, and resolved the
     * same way: let the database arbitrate rather than checking first. V18
     * replaced V5's single index with one per scope, so the same statement now
     * arbitrates two different uniqueness rules and neither is expressed here.
     *
     * @param spaceId null for the caller's personal list; a Space for the shared
     *                one, in which case {@code userId} records only who opened
     *                it
     */
    @Transactional
    public UUID openListId(UUID userId, UUID spaceId) {
        Optional<UUID> existing = findOpenList(userId, spaceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        jdbc.sql("""
                        insert into shopping_lists (user_id, space_id, status)
                        values (?, ?, 'open')
                        on conflict do nothing
                        """)
                .param(userId)
                .param(spaceId)
                .update();

        return findOpenList(userId, spaceId).orElseThrow(() ->
                new IllegalStateException("could not open a shopping list for " + userId));
    }

    /** The personal list — V5's signature, kept so every existing caller reads unchanged. */
    @Transactional
    public UUID openListId(UUID userId) {
        return openListId(userId, null);
    }

    /**
     * A Space's list is found by the Space alone: whoever created it is not who
     * owns it, and looking it up by {@code (user_id, space_id)} would give the
     * second member their own empty list beside the shared one.
     */
    private Optional<UUID> findOpenList(UUID userId, UUID spaceId) {
        return spaceId == null
                ? jdbc.sql("""
                            select id from shopping_lists
                            where user_id = ? and space_id is null and status = 'open'
                            """)
                        .param(userId).query(UUID.class).optional()
                : jdbc.sql("select id from shopping_lists where space_id = ? and status = 'open'")
                        .param(spaceId).query(UUID.class).optional();
    }

    @Transactional(readOnly = true)
    public ShoppingList get(UUID userId, UUID spaceId) {
        Optional<UUID> listId = findOpenList(userId, spaceId);
        if (listId.isEmpty()) {
            // An empty list is a legitimate state, not a 404 — a user who has
            // never run the Act still has a shopping list, it just has nothing
            // in it. Creating a row here would write on a GET.
            return new ShoppingList(null, List.of());
        }
        return new ShoppingList(listId.get(), itemsOf(listId.get()));
    }

    @Transactional(readOnly = true)
    public ShoppingList get(UUID userId) {
        return get(userId, null);
    }

    private List<Item> itemsOf(UUID listId) {
        return jdbc.sql("""
                        select id, name, quantity, unit, category, checked, sources::text as sources
                        from shopping_list_items
                        where list_id = ?
                        order by array_position(?::text[], category), lower(name)
                        """)
                .param(listId)
                .param(ShoppingListConverter.CATEGORIES.toArray(String[]::new))
                .query((rs, row) -> new Item(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("quantity"),
                        rs.getString("unit"),
                        rs.getString("category"),
                        rs.getBoolean("checked"),
                        parseSources(rs.getString("sources"))))
                .list();
    }

    /** One recipe's contribution to one line, kept so the total can be recomputed. */
    record Contribution(UUID saveId, String quantity, String unit) {
    }

    /**
     * Adds a recipe's items to the open list, merging with what is already
     * there.
     *
     * <p>Merging is the feature, not a detail: two recipes wanting onions must
     * produce one line saying three onions, or the user is doing the reconciling
     * the Act exists to do for them.
     *
     * <p><b>Exactly idempotent per (list, save)</b>, and that took a rewrite to
     * get right. The obvious implementation — add this recipe's quantity to
     * whatever is already on the line — is wrong the second time it runs, and
     * the runner re-delivers a job on any transient failure or stale claim.
     * Observed live: re-running one conversion took garlic from 7 cloves to 10
     * and olive oil from 4 tbsp to 6, silently, with no error anywhere.
     *
     * <p>So a line does not store an accumulated total. It stores <em>every
     * contributing recipe's own quantity</em> and recomputes the total by
     * folding them. Re-running a conversion replaces that recipe's
     * contribution, which makes the result depend only on the set of recipes on
     * the list — not on how many times each was converted.
     *
     * <p>A Space's list merges two <em>people's</em> recipes by that same rule,
     * with no change here at all: the contribution is keyed by save, and whose
     * save it is has never entered the arithmetic.
     *
     * @param spaceId null for the caller's own list; the Space when the recipe
     *                being converted lives in one, so four people planning
     *                Saturday shop from one list
     * @return how many distinct items the list gained
     */
    @Transactional
    public int addFromSave(UUID userId, UUID spaceId, UUID saveId,
                           List<ShoppingListConverter.ItemDraft> drafts) {
        UUID listId = openListId(userId, spaceId);

        // Lines this recipe used to contribute to but no longer does — e.g. the
        // prompt changed, or the recipe was re-classified.
        dropContributionsOf(audienceFor(userId, spaceId), listId, saveId);

        int added = 0;
        for (ShoppingListConverter.ItemDraft draft : drafts) {
            if (mergeInto(listId, saveId, draft)) {
                added++;
            }
        }
        log.info("Shopping list {} gained {} item(s) from save {}", listId, added, saveId);
        return added;
    }

    /**
     * Removes this save's contribution from every line, deleting lines nothing
     * else contributes to and recomputing the rest.
     *
     * @param audience everyone whose cached copy of the list has to learn about
     *                 a deletion — one person for a personal list, every member
     *                 for a Space's
     */
    private void dropContributionsOf(List<UUID> audience, UUID listId, UUID saveId) {
        for (ItemRow row : rowsContributedToBy(listId, saveId)) {
            List<Contribution> remaining = row.contributions().stream()
                    .filter(c -> !c.saveId().equals(saveId))
                    .toList();
            if (remaining.isEmpty()) {
                jdbc.sql("delete from shopping_list_items where id = ?")
                        .param(row.id())
                        .update();
                tombstones.recordFor(audience, TombstoneService.SHOPPING_ITEM, row.id().toString());
            } else {
                writeContributions(row.id(), remaining);
            }
        }
    }

    /**
     * Who has to hear about a deletion from this list.
     *
     * <p>The whole point of a tombstone is the audience: a shared item ticked
     * off and cleared by one person has to disappear from four phones, and a
     * record written only for the person who tapped is a record three of them
     * never see. Read from {@code space_members} at the moment of the delete —
     * a member who joins afterwards gets the list's current contents on their
     * first pull and needs no deletion history.
     */
    private List<UUID> audienceFor(UUID userId, UUID spaceId) {
        return spaceId == null ? List.of(userId) : tombstones.membersOf(spaceId);
    }

    private record ItemRow(UUID id, String quantity, String unit, List<Contribution> contributions) {
    }

    private List<ItemRow> rowsContributedToBy(UUID listId, UUID saveId) {
        return jdbc.sql("""
                        select id, quantity, unit, sources::text as sources
                        from shopping_list_items
                        where list_id = ?
                          and sources @> ?::jsonb
                        """)
                .param(listId)
                .param("[{\"saveId\":\"" + saveId + "\"}]")
                .query((rs, row) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("quantity"),
                        rs.getString("unit"),
                        parseContributions(rs.getString("sources"))))
                .list();
    }

    /** @return true when a new row was inserted rather than an existing one updated */
    private boolean mergeInto(UUID listId, UUID saveId, ShoppingListConverter.ItemDraft draft) {
        String key = normaliseName(draft.name());
        Contribution mine = new Contribution(saveId, draft.quantity(), draft.unit());

        Optional<ItemRow> existing = jdbc.sql("""
                        select id, quantity, unit, sources::text as sources
                        from shopping_list_items
                        where list_id = ? and lower(btrim(name)) = ?
                        limit 1
                        """)
                .param(listId)
                .param(key)
                .query((rs, row) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("quantity"),
                        rs.getString("unit"),
                        parseContributions(rs.getString("sources"))))
                .optional();

        if (existing.isEmpty()) {
            jdbc.sql("""
                            insert into shopping_list_items
                                (list_id, save_id, sources, name, quantity, unit, category)
                            values (?, ?, ?::jsonb, ?, ?, ?, ?)
                            """)
                    .param(listId)
                    .param(saveId)
                    .param(serialise(List.of(mine)))
                    .param(draft.name())
                    .param(draft.quantity())
                    .param(draft.unit())
                    .param(draft.category())
                    .update();
            return true;
        }

        // Replace rather than append: this save may already be a contributor.
        List<Contribution> merged = new ArrayList<>(existing.get().contributions().stream()
                .filter(c -> !c.saveId().equals(saveId))
                .toList());
        merged.add(mine);
        writeContributions(existing.get().id(), merged);
        return false;
    }

    /** The recomputed total for a line: what the user actually reads. */
    record Total(String quantity, String unit) {
    }

    /**
     * Folds every contributing recipe's quantity into one.
     *
     * <p>Static and dependency-free on purpose: this is the arithmetic that
     * silently inflated quantities on every job retry before the rewrite, and
     * it is the one part of the merge that can be pinned without a database.
     *
     * <p>The first contribution's unit labels the line. When units disagree,
     * {@link Quantities} keeps both numbers side by side rather than converting
     * between them, so the label is the only thing that has to give — and
     * showing "200 + 1 g" is a visible oddity, where a silently converted
     * number would not be.
     */
    static Total fold(List<Contribution> contributions) {
        String quantity = null;
        String unit = null;
        boolean first = true;
        for (Contribution c : contributions) {
            if (first) {
                quantity = c.quantity();
                unit = c.unit();
                first = false;
                continue;
            }
            quantity = Quantities.merge(quantity, unit, c.quantity(), c.unit()).orElse(null);
            if (unit == null) unit = c.unit();
        }
        return new Total(quantity, unit);
    }

    /** Recomputes the displayed quantity from scratch and stores the contributions. */
    private void writeContributions(UUID itemId, List<Contribution> contributions) {
        Total total = fold(contributions);
        String quantity = total.quantity();
        String unit = total.unit();

        jdbc.sql("""
                        update shopping_list_items
                        set quantity = ?, unit = ?, sources = ?::jsonb,
                            -- A line whose quantity just changed must reappear
                            -- as outstanding: it was ticked off for a smaller
                            -- amount than the list now calls for.
                            checked = false
                        where id = ?
                        """)
                .param(quantity)
                .param(unit)
                .param(serialise(contributions))
                .param(itemId)
                .update();
    }

    private String serialise(List<Contribution> contributions) {
        try {
            return objectMapper.writeValueAsString(contributions.stream()
                    .map(c -> {
                        var map = new java.util.LinkedHashMap<String, String>();
                        map.put("saveId", c.saveId().toString());
                        if (c.quantity() != null) map.put("quantity", c.quantity());
                        if (c.unit() != null) map.put("unit", c.unit());
                        return map;
                    })
                    .toList());
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise shopping list provenance", e);
        }
    }

    private List<Contribution> parseContributions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Contribution> parsed = new ArrayList<>();
            objectMapper.readTree(json).forEach(node -> {
                String id = node.path("saveId").asString(null);
                if (id == null) return;
                try {
                    parsed.add(new Contribution(
                            UUID.fromString(id),
                            node.path("quantity").asString(null),
                            node.path("unit").asString(null)));
                } catch (IllegalArgumentException ignored) {
                    // A malformed id costs a breadcrumb, not the item.
                }
            });
            return List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The access rule for an item, as a {@code where} fragment.
     *
     * <p>An item id arrives with no scope attached, so every mutation proves the
     * caller may touch it in the statement itself rather than loading the row
     * and then judging it — the service layer is the access-control boundary, so
     * it must never read a row it is not allowed to see. Two parameters, both
     * the caller's id: their own list, or a list belonging to a Space they are
     * in. {@code l.space_id} is null on a personal list, so the {@code exists}
     * can never accidentally admit anyone.
     */
    private static final String ITEM_VISIBLE = """
            (l.user_id = ?
             or exists (select 1 from space_members m
                        where m.space_id = l.space_id and m.user_id = ?))
            """;

    @Transactional
    public void setChecked(UUID userId, UUID itemId, boolean checked) {
        int updated = jdbc.sql("""
                        update shopping_list_items i
                        set checked = ?
                        from shopping_lists l
                        where i.id = ? and i.list_id = l.id and
                        """ + ITEM_VISIBLE)
                .param(checked)
                .param(itemId)
                .param(userId)
                .param(userId)
                .update();

        if (updated == 0) {
            throw new NotFoundException("That item is not on your list.");
        }
    }

    @Transactional
    public void deleteItem(UUID userId, UUID itemId) {
        // `returning` the list's space, because the audience for the tombstone
        // is not knowable before the delete resolves which list this item was
        // on — and on a shared list it is four people, not one.
        List<UUID> spaceIds = jdbc.sql("""
                        delete from shopping_list_items i
                        using shopping_lists l
                        where i.id = ? and i.list_id = l.id and
                        """ + ITEM_VISIBLE + """
                        returning l.space_id
                        """)
                .param(itemId)
                .param(userId)
                .param(userId)
                .query((rs, row) -> rs.getObject("space_id", UUID.class))
                .list();
        if (spaceIds.isEmpty()) {
            throw new NotFoundException("That item is not on your list.");
        }
        tombstones.recordFor(audienceFor(userId, spaceIds.getFirst()),
                TombstoneService.SHOPPING_ITEM, itemId.toString());
    }

    /**
     * Clearing checked items is what "I've been shopping" means in practice.
     *
     * <p>{@code returning} rather than a plain row count, because each removed
     * id needs a tombstone: a phone that ticked things off in the shop and
     * cleared them there has the deletions locally already, but a second device
     * — or the same one after a reinstall — learns about them only from here.
     */
    @Transactional
    public int clearChecked(UUID userId, UUID spaceId) {
        // Scoped to one list rather than to "everything this caller can reach":
        // clearing the Space's list must not also empty the caller's personal
        // one, and one button doing both would be unrecoverable.
        Optional<UUID> listId = findOpenList(userId, spaceId);
        if (listId.isEmpty()) {
            return 0;
        }
        List<UUID> removed = jdbc.sql("""
                        delete from shopping_list_items
                        where list_id = ? and checked
                        returning id
                        """)
                .param(listId.get())
                .query(UUID.class)
                .list();
        List<UUID> audience = audienceFor(userId, spaceId);
        for (UUID id : removed) {
            tombstones.recordFor(audience, TombstoneService.SHOPPING_ITEM, id.toString());
        }
        return removed.size();
    }

    @Transactional
    public int clearChecked(UUID userId) {
        return clearChecked(userId, null);
    }

    /** Matches how a shopper reads a name: case and spacing are not differences. */
    static String normaliseName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The wire shape stays a plain list of save ids — clients want provenance, not arithmetic. */
    private List<UUID> parseSources(String json) {
        return parseContributions(json).stream().map(Contribution::saveId).toList();
    }
}
