package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The lexical DEFAULT scorer: BM25 over title, snippet, displayed domain and address, no model, no
 * sidecar. It orders by real
 * word overlap (umlauts intact), selects only candidates that matched at all, and serves the same
 * relevance-assessment seam the AI reranker does — so "KI-Reranker aus" is a working search, not a
 * degraded one.
 */
public class Bm25CandidateRerankerTest {

    private static SearchResultCandidate candidate(String id, String title, String snippet) {
        return new SearchResultCandidate(id, "snap", "https://example.test/" + id,
                "https://example.test/" + id, title, snippet, "example.test", 1, "c", "b", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    @Test
    public void ordersByLexicalOverlapAndDropsZeroMatches() {
        Bm25CandidateReranker reranker = new Bm25CandidateReranker();
        List<SearchResultCandidate> candidates = Arrays.asList(
                candidate("c-1", "Kochrezepte für Nudeln", "Pasta einfach selbst machen"),
                candidate("c-2", "Hühner richtig halten", "Hühner brauchen Auslauf und Schutz — "
                        + "Hühner sind Herdentiere"),
                candidate("c-3", "Hühner im Garten", "kurze Notiz"));

        SearchResultRerankingResult result = reranker.rerank("hühner halten",
                candidates, null);

        assertEquals(SearchResultRerankingOutcome.SUCCESS, result.outcome);
        assertEquals("the strongest lexical match leads", "c-2",
                result.reranked.get(0).candidate.candidateId);
        for (RerankedSearchResultCandidate selected : result.selected) {
            assertTrue("zero-overlap candidates are honest non-matches, never selected",
                    !"c-1".equals(selected.candidate.candidateId));
        }
        assertEquals(Bm25CandidateReranker.MODEL_NAME, result.modelName);
    }

    /**
     * Bing, "Java 21 LTS release", rank 4: "Latest Releases | Adoptium", a snippet cut off before it
     * names a version, the breadcrumb "adoptium.net › temurin › releases". Only the address carries
     * the "21". Scored on title and snippet alone it was a zero match and never reached the frontier.
     */
    @Test
    public void aHitWhoseOnlyMatchIsInItsAddressIsStillSelected() {
        SearchResultCandidate adoptium = new SearchResultCandidate("c-4", "snap",
                "https://adoptium.net/temurin/releases/?version=21",
                "https://adoptium.net/temurin/releases/?version=21", "Latest Releases | Adoptium",
                "There are multiple different ways to get Eclipse Temurin beyond direct downloads.",
                "https://adoptium.net › temurin › releases", 4, "c", "b", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
        List<SearchResultCandidate> candidates = Arrays.asList(
                candidate("c-1", "Consolidated JDK 21 Release Notes", "Java 21 release notes"),
                adoptium);

        SearchResultRerankingResult result = new Bm25CandidateReranker().rerank(
                "Java 21 LTS release", candidates, null);

        assertEquals(SearchResultRerankingOutcome.SUCCESS, result.outcome);
        assertEquals("both hits are selected", 2, result.selected.size());
        assertEquals("the hit matching in title and snippet still leads", "c-1",
                result.selected.get(0).candidate.candidateId);
    }

    @Test
    public void aPercentEncodedAddressMatchesItsDecodedWord() {
        SearchResultCandidate encoded = new SearchResultCandidate("c-2", "snap",
                "https://example.test/ratgeber/h%C3%BChner/", "https://example.test/ratgeber/h%C3%BChner/",
                "Ratgeber", "Alles für den Garten", "example.test", 2, "c", "b", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());

        SearchResultRerankingResult result = new Bm25CandidateReranker().rerank("hühner",
                Arrays.asList(candidate("c-1", "Pasta", "Nudeln kochen"), encoded), null);

        assertEquals(1, result.selected.size());
        assertEquals("c-2", result.selected.get(0).candidate.candidateId);
        assertEquals("the hit keeps its address", "https://example.test/ratgeber/h%C3%BChner/",
                result.selected.get(0).candidate.resolvedTargetUrl);
        assertEquals("an undecodable address is scored as it stands", "example.test/50%-off",
                Bm25CandidateReranker.addressWords("https://example.test/50%-off"));
    }

    @Test
    public void assessScoresArbitraryDocumentsWithoutAModel() {
        LinkedHashMap<String, String> documents = new LinkedHashMap<String, String>();
        documents.put("d-1", "Die Hühnerhaltung im eigenen Garten");
        documents.put("d-2", "Ein Artikel über Aktienfonds");

        com.aresstack.askai.research.domain.search.RelevanceAssessment assessment =
                new Bm25CandidateReranker().assess("hühnerhaltung garten", documents, null);

        assertTrue(assessment.isAvailable());
        assertEquals("d-1", assessment.getScores().get(0).getCandidateId());
        assertTrue(assessment.getScores().get(0).getRelevance()
                > assessment.getScores().get(1).getRelevance());
    }
}
