package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.domain.search.RelevanceAssessment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 14 (D5): the ranking of a planned query, its BM25 fallback and its top-N visit limit. */
public class PlannedQueryRerankerTest {

    private static final CancellationSignal NEVER = new CancellationSignal() {
        public boolean isCancelled() {
            return false;
        }
    };

    private static List<SearchResultCandidate> hits(int n) {
        List<SearchResultCandidate> out = new ArrayList<SearchResultCandidate>();
        for (int i = 0; i < n; i++) {
            String url = "https://site" + i + ".example/classloader";
            out.add(new SearchResultCandidate("c-" + i, "s1", url, url, "Java classloader isolation " + i,
                    "classloader isolation plugin java", "site" + i + ".example", i + 1, "r1", "b1", 1.0, 1.0,
                    Collections.<SearchResultSiteLink>emptyList()));
        }
        return out;
    }

    /** A cross-encoder that is down. */
    private static final CandidateReranker DOWN = new CandidateReranker() {
        public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                                  CancellationSignal cancellation) {
            return SearchResultRerankingResult.failure(SearchResultRerankingOutcome.RERANKER_UNAVAILABLE,
                    "cross-encoder", null, "stopped");
        }

        public RelevanceAssessment assess(String query, LinkedHashMap<String, String> documentsById,
                                          CancellationSignal cancellation) {
            return RelevanceAssessment.unavailable("stopped");
        }
    };

    @Test
    public void aStoppedRerankerRanksByKeywordsAndSaysSo() {
        PlannedQueryReranker ranking = PlannedQueryReranker.ranked(DOWN, new Bm25CandidateReranker(), 0);
        SearchResultRerankingResult result = ranking.rerank("classloader isolation", hits(4), NEVER);
        assertEquals(Bm25CandidateReranker.MODEL_NAME, result.modelName);
        assertEquals(4, result.selected.size());
        assertTrue(ranking.fellBackToKeywords());
    }

    @Test
    public void aMissingRerankerIsAFallbackFromTheStart() {
        PlannedQueryReranker ranking = PlannedQueryReranker.ranked(null, new Bm25CandidateReranker(), 0);
        assertTrue(ranking.fellBackToKeywords());
        assertTrue(ranking.assess("classloader", docs(), NEVER).isAvailable());
    }

    @Test
    public void aWorkingRerankerIsUsedAndTheTopNKept() {
        PlannedQueryReranker ranking = PlannedQueryReranker.ranked(new Bm25CandidateReranker(),
                new EngineOrderReranker(), 2);
        SearchResultRerankingResult result = ranking.rerank("classloader isolation", hits(5), NEVER);
        assertEquals(2, result.selected.size());
        assertFalse(ranking.fellBackToKeywords());
    }

    @Test
    public void chosenBm25IsNeverAFallback() {
        PlannedQueryReranker ranking = PlannedQueryReranker.keywords(new Bm25CandidateReranker(), 3);
        assertEquals(3, ranking.rerank("classloader isolation", hits(6), NEVER).selected.size());
        assertFalse(ranking.fellBackToKeywords());
    }

    private static LinkedHashMap<String, String> docs() {
        LinkedHashMap<String, String> docs = new LinkedHashMap<String, String>();
        docs.put("a", "classloader isolation in java");
        return docs;
    }
}
