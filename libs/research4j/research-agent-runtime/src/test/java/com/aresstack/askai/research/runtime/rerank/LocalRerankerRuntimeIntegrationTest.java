package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * A5 mandatory proof: the strict {@link HttpRerankerClient} against the REAL R0 local-model runtime. It
 * asks the live cross-encoder to score documents for a query and proves the client accepts the real
 * contract and that RAW_LOGIT ordering puts the clearly-relevant documents above an irrelevant one.
 *
 * <p>Prerequisites are resolved through {@link LiveLocalRerankerRuntime}, which SKIPS readably (never
 * fails) when the Java-21 launcher, the staged runtime jar, or an installed RUNNABLE model with the
 * published {@code rerank} capability is absent — so an environment with only an embedding model stays
 * green by skipping, while an environment with a real reranker RUNS end to end. A rerank-capable model
 * that then returns an error is a genuine failure and is never downgraded to a skip.</p>
 */
public class LocalRerankerRuntimeIntegrationTest {

    private LiveLocalRerankerRuntime runtime;

    @After
    public void stopRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    public void realCrossEncoderScoresAndRanksThroughTheStrictClient() throws Exception {
        runtime = LiveLocalRerankerRuntime.startOrNull();
        assumeTrue("SKIPPED: no live local runtime with a RERANK-capable model "
                + "(Java-21 launcher / staged jar / installed rerank-capable model)", runtime != null);

        String query = "how does the pf4j java plugin framework load plugins";
        List<String> documents = Arrays.asList(
                "Title: PF4J plugin framework\nSnippet: PF4J is a lightweight plugin framework for "
                        + "Java that loads plugins from directories and jars at runtime.",
                "Title: Tomato soup recipe\nSnippet: Simmer tomatoes with basil and cream for a warm "
                        + "bowl of soup on a cold day.",
                "Title: Java plugins with PF4J\nSnippet: Extension points and plugin lifecycle in the "
                        + "PF4J framework for modular Java applications.");

        List<RerankScore> scores = new HttpRerankerClient(runtime.descriptor(10))
                .rerank(query, documents).scores;
        assertEquals("the real runtime scored every submitted document", 3, scores.size());

        // Apply the productive selection policy and check the real model ranks the two PF4J documents
        // above the soup recipe — RAW_LOGIT ordering, no fixed 0.5 threshold.
        List<RerankedSearchResultCandidate> ranked = new ArrayList<RerankedSearchResultCandidate>();
        for (RerankScore score : scores) {
            ranked.add(new RerankedSearchResultCandidate(
                    fakeCandidate(score.documentIndex), score.score, 0));
        }
        SearchResultSelection result = new SearchResultSelectionPolicy(
                RerankerSelectionConfiguration.topN(10)).select(ranked);

        int soupRank = 0;
        int worstPf4jRank = 0;
        for (RerankedSearchResultCandidate c : result.reranked) {
            int docIndex = Integer.parseInt(c.candidate.candidateId);
            if (docIndex == 1) {
                soupRank = c.rerankRank;
            } else {
                worstPf4jRank = Math.max(worstPf4jRank, c.rerankRank);
            }
        }
        assertTrue("both PF4J documents outrank the irrelevant soup recipe (soup rank " + soupRank
                + ", worst PF4J rank " + worstPf4jRank + ")", soupRank > worstPf4jRank);
        assertFalse(result.selected.isEmpty());
    }

    private static com.aresstack.askai.browser.search.SearchResultCandidate fakeCandidate(int index) {
        return new com.aresstack.askai.browser.search.SearchResultCandidate(
                String.valueOf(index), "snap", "https://doc" + index + ".example", "", "t", "s", "",
                index + 1, "rc", "rb", 0.9, 0.9,
                java.util.Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>
                        emptyList());
    }
}
