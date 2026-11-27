package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.Assert.assertEquals;

/**
 * A5c proof: selection is by RAW_LOGIT relevance with no fixed 0.5 threshold — a best hit of 0.12 (and
 * even negative logits) rank correctly — plus Top-N and the optional absolute/relative/margin cut-offs.
 */
public class SearchResultSelectionPolicyTest {

    private static SearchResultCandidate candidate(String id, int originalRank) {
        return new SearchResultCandidate(id, "snap", "https://" + id + ".example", "", "T " + id,
                "S " + id, id + ".example", originalRank, "rc", "rbc", 0.9, 0.9,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    /** Score candidate c-i with the given raw logit, keeping engine order = input order. */
    private static List<RerankedSearchResultCandidate> scored(double... rawLogits) {
        List<RerankedSearchResultCandidate> list = new ArrayList<RerankedSearchResultCandidate>();
        for (int i = 0; i < rawLogits.length; i++) {
            list.add(new RerankedSearchResultCandidate(candidate("c" + i, i + 1), rawLogits[i], 0));
        }
        return list;
    }

    private static List<String> ids(List<RerankedSearchResultCandidate> list) {
        List<String> ids = new ArrayList<String>();
        for (RerankedSearchResultCandidate c : list) {
            ids.add(c.candidate.candidateId);
        }
        return ids;
    }

    @Test
    public void ordersByRawLogitEvenWithNegativeAndSubHalfScores() {
        // Best hit is 0.12 (< 0.5) and there are negative logits — pure descending order must hold.
        SearchResultSelection result = new SearchResultSelectionPolicy(
                RerankerSelectionConfiguration.topN(10))
                .select(scored(-3.4, 0.12, -0.5, -9.0));

        assertEquals(Arrays.asList("c1", "c2", "c0", "c3"), ids(result.reranked));
        assertEquals("best sub-0.5 logit is selected #1", "c1",
                result.selected.get(0).candidate.candidateId);
        assertEquals(1, result.selected.get(0).rerankRank);
        assertEquals(4, result.selected.size());
    }

    @Test
    public void appliesTopN() {
        SearchResultSelection result = new SearchResultSelectionPolicy(
                RerankerSelectionConfiguration.topN(2)).select(scored(1.0, 5.0, 3.0, 4.0));
        assertEquals(Arrays.asList("c1", "c3"), ids(result.selected));
        assertEquals(4, result.reranked.size());
    }

    @Test
    public void appliesAbsoluteMinimumFloor() {
        RerankerSelectionConfiguration config = new RerankerSelectionConfiguration(10,
                OptionalDouble.of(0.0), OptionalDouble.empty(), OptionalDouble.empty());
        SearchResultSelection result =
                new SearchResultSelectionPolicy(config).select(scored(2.0, -1.0, 0.5, -0.01));
        assertEquals(Arrays.asList("c0", "c2"), ids(result.selected));
    }

    @Test
    public void appliesMaximumDropFromBest() {
        RerankerSelectionConfiguration config = new RerankerSelectionConfiguration(10,
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.of(1.0));
        // best = 5.0; keep only scores >= 4.0
        SearchResultSelection result =
                new SearchResultSelectionPolicy(config).select(scored(5.0, 4.5, 3.9, 4.0));
        assertEquals(Arrays.asList("c0", "c1", "c3"), ids(result.selected));
    }

    @Test
    public void tailTrustGateKeepsOnlyTopWhenNoClearLeader() {
        RerankerSelectionConfiguration config = new RerankerSelectionConfiguration(10,
                OptionalDouble.empty(), OptionalDouble.of(1.0), OptionalDouble.empty());
        // top margin 5.0 - 4.9 = 0.1 < 1.0 -> only the single winner
        SearchResultSelection result =
                new SearchResultSelectionPolicy(config).select(scored(5.0, 4.9, 4.8));
        assertEquals(Collections.singletonList("c0"), ids(result.selected));
        assertEquals("full order still available", 3, result.reranked.size());
    }

    @Test
    public void emptyInputYieldsEmptyResult() {
        SearchResultSelection result = new SearchResultSelectionPolicy(
                RerankerSelectionConfiguration.topN(5))
                .select(new ArrayList<RerankedSearchResultCandidate>());
        assertEquals(0, result.selected.size());
        assertEquals(0, result.reranked.size());
    }
}
