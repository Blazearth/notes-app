package com.weavr.api.job;

/**
 * Job types are plain strings in the database so that adding one is a data
 * change. These constants name the ones the pipeline dispatches on.
 */
public final class JobType {

    /** Entry point for a new save: runs the text-extraction cascade onward. */
    public static final String PROCESS_SAVE = "process_save";

    /** Stage 2: runs the Gemini classify-and-extract call on extracted text. */
    public static final String CLASSIFY_SAVE = "classify_save";

    /**
     * Stage 3: fills gaps in {@code structured_data} from external APIs (TMDB,
     * Google Places). Costs no Gemini request — a different provider entirely,
     * which is most of the reason it is worth doing.
     */
    public static final String ENRICH_SAVE = "enrich_save";

    /**
     * Stage 4: embeds the classified save for semantic search. Runs after the
     * save is already {@code ready} — being findable by similarity is an
     * enhancement, not a precondition for the save being useful.
     *
     * <p>Enqueued by {@code EnrichSaveHandler} rather than by the classify
     * step, so the vector is built from the <em>enriched</em> structured data.
     */
    public static final String EMBED_SAVE = "embed_save";

    /**
     * Stage 5, and only for a save in a shared Space: compare its embedding
     * against everything else in that Space and suggest merges. Costs no model
     * request — the comparison is a pgvector query.
     */
    public static final String DETECT_DUPLICATES = "detect_duplicates";

    /**
     * The Act: a saved recipe becomes lines on the user's shopping list. A job
     * rather than inline work because it spends a Gemini request, so it must be
     * able to park when the daily budget is gone rather than failing a tap.
     */
    public static final String CONVERT_TO_SHOPPING_LIST = "convert_to_shopping_list";

    private JobType() {
    }
}
