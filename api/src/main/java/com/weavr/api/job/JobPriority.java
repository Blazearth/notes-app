package com.weavr.api.job;

/**
 * Claim order for the queue — {@code jobs.priority}, descending.
 *
 * <p>The column and the {@code order by priority desc} have existed since V1,
 * but nothing ever set a value: every enqueue went through {@link
 * JobQueue#enqueueForUser} which hardcoded {@code 0}, so the whole queue was
 * FIFO. That produced a real inversion — a weekly digest that nobody is waiting
 * for competed on equal terms with a Reel somebody just shared and is watching
 * a spinner for.
 *
 * <p>The tiers are coarse on purpose. Fine-grained priorities invite a
 * starvation bug: with {@code order by priority desc, run_after} a steady
 * trickle of high-priority work can keep low-priority jobs permanently unclaimed
 * — which is survivable for a digest, and would not be for anything a user is
 * owed. Keeping the span small, and putting only genuinely deferrable work at
 * the bottom, is what makes that acceptable here.
 */
public final class JobPriority {

    /**
     * Somebody is actively waiting on this exact save.
     *
     * <p>Reserved rather than used today: the API has no signal for "this save
     * is open on screen". Defined so the tier exists when there is one, and so
     * the gap between {@link #INTERACTIVE} and the ceiling is deliberate rather
     * than accidental.
     */
    public static final int USER_WAITING = 100;

    /**
     * The default for anything on a save's critical path — the work between
     * sharing something and it being usable.
     */
    public static final int INTERACTIVE = 50;

    /**
     * The save is already {@code ready} and readable without this. Enrichment
     * adds a director and an address; embedding makes it findable by similarity.
     * Both are improvements to something that already works, so they yield to a
     * save that has not got there yet.
     */
    public static final int ENHANCEMENT = 10;

    /**
     * Nobody is waiting. Digests are generated on demand but read minutes to
     * hours later; duplicate detection is a suggestion in a shared Space.
     */
    public static final int BACKGROUND = 0;

    private JobPriority() {
    }
}
