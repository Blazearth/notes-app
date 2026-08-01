package com.weavr.api.enrich;

import java.util.Map;
import java.util.Optional;

/**
 * Fills gaps in a save's {@code structured_data} from an external API.
 *
 * <p>One implementation per knowledge type, discovered by Spring and keyed by
 * {@link #knowledgeType()} — the same "adding a type is data, not code" shape as
 * {@link com.weavr.api.gemini.KnowledgeTypeRegistry}. A {@code book} type would
 * be one class against Google Books and no change anywhere else; it is not here
 * because there is no {@code book} knowledge type yet, and an enricher for a
 * type nothing produces is untested code pretending to be a feature.
 *
 * <p>None of this spends a Gemini request. That is the whole reason enrichment
 * is worth doing at all on a 500-a-day budget: TMDB knows the director for free,
 * and asking a model to recall one is both a request and a hallucination risk.
 */
public interface Enricher {

    /** The {@code knowledge_type} this enricher handles. */
    String knowledgeType();

    /**
     * @param structuredData what the model extracted; never mutated
     * @return only the fields to <em>add</em>, or empty when the source had
     *         nothing or could not be trusted to be about the right thing.
     *         Returning the whole map would invite overwriting what the user's
     *         own content said, which {@link EnrichSaveHandler} exists to
     *         prevent.
     */
    Optional<Map<String, Object>> enrich(Map<String, Object> structuredData);
}
