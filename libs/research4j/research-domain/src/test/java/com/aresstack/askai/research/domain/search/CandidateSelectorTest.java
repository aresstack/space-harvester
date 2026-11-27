package com.aresstack.askai.research.domain.search;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Selection is a DECISION about a run, not a state of its hits: one run can be selected from several times,
 * differently, without anything being written into a candidate. And relevance stays separate from
 * selection — diversity may only spread the choice among hits that are relevant in the first place.
 */
public class CandidateSelectorTest {

    private static SearchCandidate candidate(String id, String domain) {
        return new SearchCandidate(id, "https://" + domain + "/" + id, "Titel " + id, "Snippet " + id,
                domain, Arrays.asList(new SearchOccurrence("duckduckgo", 1, 1,
                        "https://" + domain + "/" + id)));
    }

    /** Five hits: three from one domain, two from others. */
    private static SearchRun run() {
        List<SearchCandidate> candidates = Arrays.asList(
                candidate("c1", "wiki.org"), candidate("c2", "wiki.org"), candidate("c3", "wiki.org"),
                candidate("c4", "kochbuch.de"), candidate("c5", "uni.edu"));
        return SearchRun.discovered("run-1", "truthahnragout", "ORIENTATION_SERP_SCAN",
                SearchRun.Status.RESULTS, SearchRun.StopReason.NO_CONTINUATION,
                Collections.<DiscoveryBatch>emptyList(), candidates);
    }

    private static RelevanceAssessment relevance(double... scores) {
        List<RelevanceAssessment.Score> ranked = new ArrayList<RelevanceAssessment.Score>();
        for (int index = 0; index < scores.length; index++) {
            ranked.add(new RelevanceAssessment.Score("c" + (index + 1), scores[index]));
        }
        return RelevanceAssessment.of("bge-reranker", ranked);
    }

    private static SearchStrategyProfile profileWith(SearchStrategyProfile.CandidateSelection policy) {
        return new SearchStrategyProfile("TEST", SearchStrategyProfile.ResultAcquisition.VISIT_RESULTS, 2,
                SearchStrategyProfile.AcquisitionOrder.COLLECT_THEN_SELECT, policy, 8, 3,
                SearchStrategyProfile.ObstaclePolicy.SKIP, SearchStrategyProfile.LinkExpansion.NONE, 0,
                SearchStrategyProfile.ProviderPolicy.DUCKDUCKGO_ONLY);
    }

    @Test
    public void oneRunCanCarrySeveralDifferentSelectionsAndNoneTouchesACandidate() {
        SearchRun run = run();

        SelectionDecision diverse = CandidateSelector.select("s1", run, relevance(0.9, 0.8, 0.7, 0.6, 0.5),
                profileWith(SearchStrategyProfile.CandidateSelection.DIVERSE_RELEVANT), 3, null);
        SelectionDecision user = CandidateSelector.select("s2", run, null,
                profileWith(SearchStrategyProfile.CandidateSelection.USER_SELECTED), 3,
                Arrays.asList("c5", "c2"));
        SelectionDecision agent = CandidateSelector.select("s3", run, null,
                profileWith(SearchStrategyProfile.CandidateSelection.AGENT_SELECTED), 3,
                Arrays.asList("c4"));

        assertEquals("run-1", diverse.getSearchRunId());
        assertEquals(Arrays.asList("c5", "c2"), user.selectedCandidateIds());
        assertEquals("the user's order is the selection's order", 1, user.getSelected().get(0).getOrdinal());
        assertEquals(Arrays.asList("c4"), agent.selectedCandidateIds());
        assertFalse(diverse.selectedCandidateIds().equals(user.selectedCandidateIds()));
        // The run and its hits are untouched by any of it.
        assertEquals(5, run.getCandidates().size());
        assertEquals("Titel c4", run.candidate("c4").getTitle());
    }

    @Test
    public void diversitySpreadsAcrossDomainsButOnlyAmongRelevantHits() {
        // c1..c3 are strong and all from wiki.org; c4 is mid; c5 is far below - clearly irrelevant.
        SelectionDecision decision = CandidateSelector.select("s1", run(),
                relevance(0.95, 0.92, 0.90, 0.80, 0.10),
                profileWith(SearchStrategyProfile.CandidateSelection.DIVERSE_RELEVANT), 3, null);

        List<String> picked = decision.selectedCandidateIds();
        assertTrue("the best hit is always in", picked.contains("c1"));
        assertTrue("a second domain is preferred over a third wiki page", picked.contains("c4"));
        assertFalse("a clearly irrelevant hit must not be picked just for its domain",
                picked.contains("c5"));
        assertEquals(3, picked.size());
        assertTrue(decision.getSelected().get(1).getSelectionReason().contains("new domain"));
    }

    @Test
    public void aSelectionIsShorterRatherThanPaddedWithIrrelevantHits() {
        // Only two hits are relevant at all; the limit asks for four.
        SelectionDecision decision = CandidateSelector.select("s1", run(),
                relevance(0.99, 0.97, 0.05, 0.04, 0.03),
                profileWith(SearchStrategyProfile.CandidateSelection.DIVERSE_RELEVANT), 4, null);

        assertEquals(2, decision.getSelected().size());
        assertEquals(Arrays.asList("c1", "c2"), decision.selectedCandidateIds());
    }

    @Test
    public void topRankedTakesTheBestHitsEvenWhenTheyShareADomain() {
        SelectionDecision decision = CandidateSelector.select("s1", run(),
                relevance(0.95, 0.92, 0.90, 0.80, 0.70),
                profileWith(SearchStrategyProfile.CandidateSelection.TOP_RANKED), 3, null);

        assertEquals(Arrays.asList("c1", "c2", "c3"), decision.selectedCandidateIds());
        assertEquals("rank 1", decision.getSelected().get(0).getSelectionReason());
    }

    @Test
    public void withoutARelevanceAssessmentNothingIsSelectedAutomaticallyAndTheHitsSurvive() {
        SearchRun run = run();

        SelectionDecision decision = CandidateSelector.select("s1", run,
                RelevanceAssessment.unavailable("reranker endpoint unreachable"),
                profileWith(SearchStrategyProfile.CandidateSelection.TOP_RANKED), 3, null);

        assertTrue(decision.isBlocked());
        assertTrue(decision.isEmpty());
        assertTrue(decision.getBlockedReason(),
                decision.getBlockedReason().contains("reranker endpoint unreachable"));
        assertTrue("nothing may be opened in raw engine order",
                decision.getBlockedReason().contains("raw engine order"));
        assertEquals("the discovery result is not lost by a reranker failure",
                5, run.getCandidates().size());

        // An explicit pick still works without any relevance model.
        SelectionDecision explicit = CandidateSelector.select("s2", run, null,
                profileWith(SearchStrategyProfile.CandidateSelection.USER_SELECTED), 3,
                Arrays.asList("c3"));
        assertFalse(explicit.isBlocked());
        assertEquals(Arrays.asList("c3"), explicit.selectedCandidateIds());
    }

    @Test
    public void anExplicitSelectionIgnoresUnknownIdsInsteadOfInventingThem() {
        SelectionDecision decision = CandidateSelector.select("s1", run(), null,
                profileWith(SearchStrategyProfile.CandidateSelection.USER_SELECTED), 5,
                Arrays.asList("c2", "does-not-exist", "c2", "c5"));

        assertEquals("unknown and duplicate ids are dropped", Arrays.asList("c2", "c5"),
                decision.selectedCandidateIds());
    }

    @Test
    public void hybridStartsFromRankAndCorrectsForDiversity() {
        SelectionDecision decision = CandidateSelector.select("s1", run(),
                relevance(0.95, 0.94, 0.93, 0.92, 0.91),
                profileWith(SearchStrategyProfile.CandidateSelection.HYBRID), 3, null);

        List<String> picked = decision.selectedCandidateIds();
        assertEquals("c1", picked.get(0));
        assertTrue("with comparable relevance, other domains come before more of the same",
                picked.contains("c4") && picked.contains("c5"));
    }

    @Test
    public void theDecisionDescribesItselfWithItsPolicyAndRun() {
        SelectionDecision decision = CandidateSelector.select("s1", run(), relevance(0.9, 0.8, 0.7, 0.6, 0.5),
                profileWith(SearchStrategyProfile.CandidateSelection.TOP_RANKED), 2, null);

        assertEquals("selection=s1 run=run-1 policy=TOP_RANKED picked=2", decision.describe());
        assertEquals("TEST", decision.getProfileName());
    }
}
