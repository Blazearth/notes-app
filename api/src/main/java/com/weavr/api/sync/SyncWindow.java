package com.weavr.api.sync;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Where one {@code GET /v1/sync} page ends.
 *
 * <p>Pure and static, with no database and no Spring, because this is the one
 * piece of the delta that can silently lose a row and the only piece that can be
 * pinned without a Postgres. {@link SyncService} runs the queries; this decides
 * the boundary they get trimmed to.
 *
 * <h2>Why the cursor is a timestamp and not {@code (updated_at, id)}</h2>
 *
 * <p>The plan in {@code docs/local-first.md} sketched a keyset on
 * {@code (updated_at, id)}, which is the right shape for paging one table. It
 * cannot work here: this endpoint pages <em>seven</em> tables against one shared
 * cursor, and four of them have no scalar id to break a tie on —
 * {@code save_item_states} is keyed on (save, user, item path),
 * {@code entity_states} on (user, entity key), {@code collection_overrides} on
 * (user, type, subject) and {@code space_members} on (space, user). A single
 * {@code untilId} handed back to all seven would be meaningless in six of them,
 * and "id &gt; that" would skip rows rather than resume after them.
 *
 * <h2>What replaces it</h2>
 *
 * <p>The cursor is {@code updated_at} alone and queries are strictly
 * {@code > since}. Two rows written in the same transaction share a timestamp to
 * the microsecond, so a page must never end <em>inside</em> such a group — that
 * is the loop-or-drop failure the keyset was there to prevent. So:
 *
 * <ol>
 *   <li>Each entity type is fetched with {@code limit + 1} rows in
 *       {@code updated_at} order. More than {@code limit} back means that type
 *       has more to give.</li>
 *   <li>If nothing overflowed, the page is everything and {@code until} is the
 *       newest timestamp seen.</li>
 *   <li>If anything overflowed, {@code until} is the <em>smallest</em> of the
 *       overflowing types' {@code limit}-th timestamps, and every type is
 *       re-read with an inclusive {@code <= until} bound and no limit. That
 *       closes the page on a whole-timestamp boundary: every row at or before
 *       {@code until} is in this page, every row after it is in the next, and
 *       {@code until > since} strictly, so a client always makes progress.</li>
 * </ol>
 *
 * <p>The cost of step 3 is that a capped page can exceed {@code limit} by the
 * size of one timestamp group — a handful of rows from one transaction. The
 * alternative, trimming mid-group, is a page that either re-delivers forever or
 * loses a row, and re-delivery is only harmless because <em>apply is an
 * upsert-by-id and the watermark advances only on success</em>. That property is
 * what makes the deliberate inclusiveness below safe.
 */
public final class SyncWindow {

    private SyncWindow() {
    }

    /**
     * @param until   the newest cursor this page covers, inclusive. Never null,
     *                and never earlier than {@code since}.
     * @param hasMore whether the client should immediately ask again
     * @param capped  true when {@code until} was pulled back below the newest
     *                row available, i.e. the caller must re-read each type with
     *                the {@code <= until} bound rather than using what it has
     */
    public record Window(Instant until, boolean hasMore, boolean capped) {
    }

    /**
     * Decides the page boundary from the cursor timestamps each entity type
     * returned.
     *
     * @param pages one list per entity type, each of at most {@code limit + 1}
     *              cursor timestamps in ascending order — a type with nothing
     *              new contributes an empty list
     * @param since the request's cursor; {@link Instant#EPOCH} for a first sync
     * @param limit the per-type page size, at least 1
     */
    public static Window cap(Collection<? extends List<Instant>> pages, Instant since, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        Instant newest = null;
        Instant cap = null;
        for (List<Instant> page : pages) {
            if (page.isEmpty()) {
                continue;
            }
            Instant last = page.get(page.size() - 1);
            if (newest == null || last.isAfter(newest)) {
                newest = last;
            }
            if (page.size() > limit) {
                // The limit-th row is the last one that fits. Everything after
                // it belongs to a later page.
                Instant boundary = page.get(limit - 1);
                if (cap == null || boundary.isBefore(cap)) {
                    cap = boundary;
                }
            }
        }

        if (cap != null) {
            // Strictly after `since`, because every fetched row was, so a client
            // sending this back cannot receive the same window again.
            return new Window(cap, true, true);
        }
        // Nothing overflowed: this page is the whole tail. `since` when there was
        // nothing at all, so an idle client's cursor stops drifting forward on a
        // clock it does not own.
        return new Window(newest == null ? since : newest, false, false);
    }
}
