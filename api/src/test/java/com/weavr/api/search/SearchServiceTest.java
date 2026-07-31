package com.weavr.api.search;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RRF fusion arithmetic, isolated from the database.
 *
 * <p>This is the part of hybrid search that is easy to get subtly wrong and
 * impossible to notice: a fusion bug does not throw, it just quietly returns a
 * worse ordering than either half would have alone, and nobody can tell without
 * a relevance benchmark that does not exist.
 */
class SearchServiceTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static List<UUID> ids(List<SearchService.Hit> hits) {
        return hits.stream().map(SearchService.Hit::id).toList();
    }

    /**
     * The entire reason hybrid search exists: agreement between the two halves
     * beats a strong showing in only one. With k=60, a save at rank 2 and 2
     * scores 2/62 ≈ 0.0323, against 1/61 ≈ 0.0164 for a save that is first in
     * one list and absent from the other.
     */
    @Test
    void aResultBothHalvesLikeBeatsOneThatOnlyOneHalfRanksFirst() {
        List<SearchService.Hit> hits = SearchService.fuse(
                List.of(A, B),
                List.of(C, B),
                10);

        assertThat(ids(hits)).startsWith(B);
        assertThat(hits.getFirst().matchedBoth()).isTrue();
    }

    @Test
    void preservesOrderWithinASingleList() {
        List<SearchService.Hit> hits = SearchService.fuse(List.of(A, B, C), List.of(), 10);

        assertThat(ids(hits)).containsExactly(A, B, C);
    }

    /** Full-text produced nothing usable — a query of near-stopwords. */
    @Test
    void returnsTheSemanticHalfAloneWhenFullTextFindsNothing() {
        List<SearchService.Hit> hits = SearchService.fuse(List.of(), List.of(C, D), 10);

        assertThat(ids(hits)).containsExactly(C, D);
        assertThat(hits.getFirst().fullTextRank()).isZero();
        assertThat(hits.getFirst().semanticRank()).isEqualTo(1);
    }

    /** The embedding service was unavailable; a worse search beats a 500. */
    @Test
    void returnsTheFullTextHalfAloneWhenThereIsNoQueryEmbedding() {
        List<SearchService.Hit> hits = SearchService.fuse(List.of(A, B), List.of(), 10);

        assertThat(ids(hits)).containsExactly(A, B);
        assertThat(hits.getFirst().semanticRank()).isZero();
    }

    @Test
    void recordsWhichHalfFoundEachResultAndAtWhatRank() {
        List<SearchService.Hit> hits = SearchService.fuse(List.of(A, B), List.of(B, A), 10);

        SearchService.Hit first = hits.getFirst();
        assertThat(first.fullTextRank()).isPositive();
        assertThat(first.semanticRank()).isPositive();
        assertThat(first.matchedBoth()).isTrue();
    }

    /**
     * Ranks are 1-based. Starting at 0 makes the first result's contribution
     * 1/60 rather than 1/61 — a gap larger than every subsequent one — which
     * silently over-weights whichever list the code happens to walk first.
     */
    @Test
    void usesOneBasedRanksSoTheFirstResultIsNotOverWeighted() {
        double topOfOneList = SearchService.fuse(List.of(A), List.of(), 10).getFirst().score();

        assertThat(topOfOneList).isEqualTo(1.0 / 61);
    }

    /**
     * Identical fused scores must not reorder between identical requests —
     * without a tiebreak that reads as a bug to anyone paging results.
     */
    @Test
    void ordersTiesDeterministically() {
        List<UUID> first = ids(SearchService.fuse(List.of(A, B), List.of(B, A), 10));
        List<UUID> again = ids(SearchService.fuse(List.of(A, B), List.of(B, A), 10));

        assertThat(first).isEqualTo(again);
    }

    /**
     * The limit applies to the fused ordering, not to each half beforehand —
     * so a save that is <em>last</em> in both lists can still come out on top.
     * C is 3rd of 3 twice (2/63 ≈ 0.0317) and beats the two saves that lead
     * their own list but appear in only one (1/61 ≈ 0.0164).
     *
     * <p>Note how fine the margins are: 1st-and-3rd (1/61 + 1/63 = 0.032266)
     * genuinely beats 2nd-and-2nd (2/62 = 0.032258). That is correct RRF and
     * not an artefact — an earlier version of this test asserted the opposite
     * and was wrong.
     */
    @Test
    void appliesTheLimitAfterFusingSoALowRankedAgreementStillWins() {
        UUID e = UUID.fromString("00000000-0000-0000-0000-00000000000e");

        List<SearchService.Hit> hits = SearchService.fuse(
                List.of(A, B, C),
                List.of(D, e, C),
                3);

        assertThat(hits).hasSize(3);
        assertThat(ids(hits)).startsWith(C);
        assertThat(hits.getFirst().matchedBoth()).isTrue();
    }

    @Test
    void returnsNothingWhenBothHalvesAreEmpty() {
        assertThat(SearchService.fuse(List.of(), List.of(), 10)).isEmpty();
    }
}
