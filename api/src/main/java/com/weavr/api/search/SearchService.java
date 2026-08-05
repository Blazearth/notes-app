package com.weavr.api.search;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.weavr.api.embed.EmbeddingClient;
import com.weavr.api.embed.PgVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Hybrid search: Postgres full-text and pgvector similarity, fused with
 * Reciprocal Rank Fusion. No separate search service, by design.
 *
 * <h2>Why RRF rather than blending the scores</h2>
 *
 * <p>The two halves produce numbers that are not comparable and never will be.
 * {@code ts_rank} is an unbounded relevance score whose magnitude depends on
 * document length and term frequency; cosine distance is a bounded
 * dissimilarity. Normalising them onto a common scale means picking a weight,
 * and that weight would need retuning every time the corpus or the embedding
 * model changed.
 *
 * <p>RRF sidesteps it entirely by <b>throwing the scores away and keeping only
 * the ranks</b>: each result contributes {@code 1 / (k + rank)} from each list
 * it appears in. A save that both halves rank highly beats one that either
 * ranks first alone, which is exactly the behaviour hybrid search exists for.
 * {@code k = 60} is the constant from the original paper and the one everyone
 * has since standardised on; it is large enough that the difference between
 * rank 1 and rank 2 does not swamp the contribution of the other list.
 *
 * <h2>Degrading, in two directions</h2>
 *
 * <ul>
 *   <li><b>No embedding for the query</b> — the embedding service is down, or
 *       the key is missing. Full-text alone still answers; a worse search beats
 *       a 500.</li>
 *   <li><b>No full-text terms</b> — the query is all stopwords ("something like
 *       that one"), so {@code plainto_tsquery} yields nothing. Vector search is
 *       the half that can still answer, and this is precisely the query shape
 *       it exists for.</li>
 * </ul>
 *
 * <p>Both halves being empty is a genuinely empty result, not an error.
 *
 * <h2>Why the vector half needs a distance cutoff</h2>
 *
 * <p>A k-nearest-neighbour search has no concept of "no match" — it returns the
 * nearest k however far away they are. Observed live before the cutoff existed:
 * searching {@code zzzzqqq} returned the user's entire library, ranked, with
 * nothing to distinguish it from a real result set. "Nothing matched" and "here
 * is everything you own" must not look the same.
 *
 * <p>The default cutoff was measured rather than remembered — real query
 * embeddings against real stored saves:
 *
 * <pre>
 *   "Noma"                          → 0.266  (the place)          hit
 *   "somewhere nice to eat in Denmark" → 0.335  (the place)       hit
 *   "Christopher Nolan"             → 0.358  (the movie)          hit
 *   "what should I cook tonight"    → 0.366  (the recipe)         hit
 *   ────────────────────────────────────────── gap ──────────────────
 *   "zzzzqqq"                       → 0.425  nearest, meaningless miss
 *   "how do I renew a passport"     → 0.490  nearest, meaningless miss
 *   "quantum chromodynamics …"      → 0.499  nearest, meaningless miss
 * </pre>
 *
 * <p>0.40 sits in the gap. <b>That gap is real but the sample is three saves
 * and seven queries</b>, so treat the number as provisional in the same way the
 * OCR tier's confidence floor is: it is a measurement, not a calibration, and a
 * few hundred real saves could move it. It is a property for that reason.
 *
 * <p>The cutoff matters less than it looks for queries that <em>do</em> match,
 * because RRF already buries a far result that the full-text half did not also
 * find. It matters entirely for queries that match nothing.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /**
     * RRF's rank constant. 60 is the value from Cormack et al. and the de facto
     * standard. It is deliberately not configurable: tuning it without an
     * evaluation set is guessing, and there is no evaluation set for search.
     */
    private static final int RANK_CONSTANT = 60;

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final SearchProperties props;

    SearchService(JdbcClient jdbc, EmbeddingClient embeddings, SearchProperties props) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.props = props;
    }

    /**
     * @param id            the save
     * @param score         fused RRF score — comparable only within one result set
     * @param fullTextRank  1-based rank in the full-text half, or 0 if absent
     * @param semanticRank  1-based rank in the vector half, or 0 if absent
     */
    public record Hit(UUID id, double score, int fullTextRank, int semanticRank) {

        public boolean matchedBoth() {
            return fullTextRank > 0 && semanticRank > 0;
        }
    }

    public List<Hit> search(UUID userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<UUID> fullText = fullTextCandidates(userId, query);

        float[] queryVector = embeddings.embedQuery(query.strip());
        List<UUID> semantic = queryVector == null
                ? List.of()
                : semanticCandidates(userId, queryVector);

        if (fullText.isEmpty() && semantic.isEmpty()) {
            return List.of();
        }
        if (queryVector == null) {
            log.debug("Search for '{}' ran full-text only — no query embedding", query);
        }

        return fuse(fullText, semantic, limit);
    }

    /**
     * Fuses two ranked lists by RRF.
     *
     * <p>Package-private and free of any database or network dependency
     * precisely so the fusion arithmetic — the part that is easy to get subtly
     * wrong and impossible to notice — is testable on its own.
     */
    static List<Hit> fuse(List<UUID> fullText, List<UUID> semantic, int limit) {
        // Insertion-ordered accumulation keyed by save id.
        var scores = new java.util.LinkedHashMap<UUID, double[]>();

        for (int i = 0; i < fullText.size(); i++) {
            // 1-based: rank 0 would make the first result's contribution
            // 1/60 larger than every subsequent gap, which is not what the
            // formula says and quietly over-weights whichever list is listed
            // first in the code.
            int rank = i + 1;
            scores.computeIfAbsent(fullText.get(i), k -> new double[3])[0] += contribution(rank);
            scores.get(fullText.get(i))[1] = rank;
        }
        for (int i = 0; i < semantic.size(); i++) {
            int rank = i + 1;
            scores.computeIfAbsent(semantic.get(i), k -> new double[3])[0] += contribution(rank);
            scores.get(semantic.get(i))[2] = rank;
        }

        List<Hit> hits = new ArrayList<>(scores.size());
        scores.forEach((id, acc) ->
                hits.add(new Hit(id, acc[0], (int) acc[1], (int) acc[2])));

        hits.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            // Deterministic tiebreak. Without one, two saves with identical
            // fused scores swap places between identical requests, which reads
            // as a bug to anyone paging through results.
            return a.id().compareTo(b.id());
        });

        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    private static double contribution(int rank) {
        return 1.0 / (RANK_CONSTANT + rank);
    }

    /**
     * Builds a prefix-aware tsquery so partial words still match — "chick"
     * finds "chicken", "past rec" finds "pasta recipe".
     *
     * <p>Strategy: run {@code websearch_to_tsquery} on the whole input (safe on
     * raw user text — no syntax errors), then OR it with a prefix tsquery built
     * from just the last word. The last word gets {@code :*} so it acts as a
     * prefix match; earlier words must match (stemmed) as normal. This gives
     * search-as-you-type behaviour without requiring trigram indexes.
     *
     * <p>If the whole query is a single word the websearch and the prefix query
     * are equivalent, but ORing them is harmless.
     *
     * <p>Ordering is by {@code ts_rank}, which honours the A/B/C weights V3
     * assigns — a save whose <em>title</em> matches outranks one that merely
     * mentions the term in an ingredient list.
     */
    private List<UUID> fullTextCandidates(UUID userId, String query) {
        // Last whitespace-delimited token, sanitised for to_tsquery syntax:
        // strip everything except letters, digits and hyphens.
        String[] tokens = query.trim().split("\\s+");
        String lastWord = tokens[tokens.length - 1].replaceAll("[^\\p{L}\\p{N}-]", "");
        // If the last word sanitises to empty (e.g. user typed a symbol),
        // fall back to plain websearch query with no prefix extension.
        boolean hasLastWord = !lastWord.isBlank();

        if (hasLastWord) {
            // websearch_to_tsquery(...) || to_tsquery('english', 'lastword:*')
            return jdbc.sql("""
                            select id
                            from saves
                            where user_id = ?
                              and status = 'ready'
                              and search_tsv @@ (
                                  websearch_to_tsquery('english', ?)
                                  || to_tsquery('english', ? || ':*')
                              )
                            order by ts_rank(search_tsv,
                                         websearch_to_tsquery('english', ?)
                                         || to_tsquery('english', ? || ':*')
                                     ) desc,
                                     created_at desc
                            limit ?
                            """)
                    .param(userId)
                    .param(query)
                    .param(lastWord)
                    .param(query)
                    .param(lastWord)
                    .param(props.candidateDepth())
                    .query(UUID.class)
                    .list();
        }

        return jdbc.sql("""
                        select id
                        from saves
                        where user_id = ?
                          and status = 'ready'
                          and search_tsv @@ websearch_to_tsquery('english', ?)
                        order by ts_rank(search_tsv, websearch_to_tsquery('english', ?)) desc,
                                 created_at desc
                        limit ?
                        """)
                .param(userId)
                .param(query)
                .param(query)
                .param(props.candidateDepth())
                .query(UUID.class)
                .list();
    }

    /**
     * {@code <=>} is cosine distance, matching the {@code vector_cosine_ops}
     * HNSW index in V1 — using a different operator here would silently fall
     * back to a sequential scan over every embedded save.
     */
    private List<UUID> semanticCandidates(UUID userId, float[] queryVector) {
        String literal = PgVectors.toLiteral(queryVector);
        // The distance filter is repeated rather than aliased: Postgres will
        // not accept an output alias in WHERE, and computing it in a subquery
        // to filter on stops the planner using the HNSW index for the ordering.
        return jdbc.sql("""
                        select id
                        from saves
                        where user_id = ?
                          and status = 'ready'
                          and embedding is not null
                          and embedding <=> ?::vector < ?
                        order by embedding <=> ?::vector
                        limit ?
                        """)
                .param(userId)
                .param(literal)
                .param(props.maxSemanticDistance())
                .param(literal)
                .param(props.candidateDepth())
                .query(UUID.class)
                .list();
    }
}
