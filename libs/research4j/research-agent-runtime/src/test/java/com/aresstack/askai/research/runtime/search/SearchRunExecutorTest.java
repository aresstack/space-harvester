package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.domain.search.SearchCandidate;
import com.aresstack.askai.research.domain.search.SearchRun;
import com.aresstack.askai.research.domain.search.SearchStrategyProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Traversal over several batches. The properties that matter: the two acquisition orders behave
 * DIFFERENTLY, deduplication keeps every provenance, and a batch that fails late does not erase what
 * earlier batches produced.
 */
public class SearchRunExecutorTest {

    private static final CancellationSignal RUNNING = new CancellationSignal() {
        public boolean isCancelled() {
            return false;
        }
    };

    private static final SearchBudgetGate OPEN = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };

    private static SearchResultCandidate hit(String url, String title, String domain) {
        return new SearchResultCandidate("x", "snap", url, url, title, "Snippet für " + title, domain,
                1, "rc", "rbc", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    /** A scripted provider: one scripted batch per call, recording how often it was asked. */
    private static final class ScriptedDiscovery implements SearchDiscovery {
        private final List<DiscoveryBatchResult> batches = new ArrayList<DiscoveryBatchResult>();
        int calls;

        ScriptedDiscovery batch(List<SearchResultCandidate> hits, String continuation) {
            batches.add(new DiscoveryBatchResult(hits, Collections.<String>emptyList(),
                    Collections.<SearchChallengeState>emptyList(), Collections.<String>emptyList(),
                    hits.isEmpty() ? InitialSearchStatus.NO_RESULTS : InitialSearchStatus.RESULTS,
                    "duckduckgo", continuation));
            return this;
        }

        ScriptedDiscovery failingBatch() {
            batches.add(new DiscoveryBatchResult(Collections.<SearchResultCandidate>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<SearchChallengeState>emptyList(),
                    Collections.singletonList("SERP layout could not be extracted"),
                    InitialSearchStatus.TECHNICAL_PROBLEM, "duckduckgo", ""));
            return this;
        }

        public DiscoveryBatchResult discover(DiscoveryRequest request, CancellationSignal cancellation,
                                             SearchBudgetGate budget) {
            return batches.get(Math.min(calls++, batches.size() - 1));
        }
    }

    private static List<SearchResultCandidate> page(int from, int count, String domainPrefix) {
        List<SearchResultCandidate> hits = new ArrayList<SearchResultCandidate>();
        for (int index = from; index < from + count; index++) {
            String domain = domainPrefix + index + ".org";
            hits.add(hit("https://" + domain + "/a", "Treffer " + index, domain));
        }
        return hits;
    }

    @Test
    public void collectThenSelectFetchesEveryAllowedBatchBeforeAnythingLooksAtThePool() throws Exception {
        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(page(1, 15, "a"), "page=2")
                .batch(page(16, 15, "b"), "page=3")
                .batch(page(31, 12, "c"), "page=4");

        SearchRun run = new SearchRunExecutor(discovery).execute("run-1", "truthahnragout", "de", null,
                SearchStrategyProfile.orientationSerpScan(), RUNNING, OPEN);

        assertEquals("page 1 must not decide the map on its own", 3, discovery.calls);
        assertEquals(3, run.getBatches().size());
        assertEquals(42, run.getCandidates().size());
        assertEquals(SearchRun.Status.RESULTS, run.getStatus());
        assertEquals(SearchRun.StopReason.BATCH_LIMIT_REACHED, run.getStopReason());
        assertEquals("discovery only: nothing was read", 0, run.readCount());
    }

    @Test
    public void progressiveStopsAsSoonAsThePoolIsEnough() throws Exception {
        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(page(1, 15, "a"), "page=2")
                .batch(page(16, 15, "b"), "page=3");

        SearchStrategyProfile progressive = new SearchStrategyProfile("PROGRESSIVE_TEST",
                SearchStrategyProfile.ResultAcquisition.SERP_ONLY, 3,
                SearchStrategyProfile.AcquisitionOrder.PROGRESSIVE,
                SearchStrategyProfile.CandidateSelection.TOP_RANKED, 0, 0,
                SearchStrategyProfile.ObstaclePolicy.SKIP, SearchStrategyProfile.LinkExpansion.NONE, 0,
                SearchStrategyProfile.ProviderPolicy.DUCKDUCKGO_ONLY);

        SearchRun run = new SearchRunExecutor(discovery).execute("run-2", "q", "de", null,
                progressive, RUNNING, OPEN);

        assertEquals("a targeted lookup must not pay for batches it will not look at", 1, discovery.calls);
        assertEquals(SearchRun.StopReason.SUFFICIENT, run.getStopReason());
        assertEquals(15, run.getCandidates().size());
    }

    @Test
    public void progressiveKeepsGoingWhileTheResultIsTooThin() throws Exception {
        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(page(1, 2, "a"), "page=2")
                .batch(page(3, 2, "b"), "page=3")
                .batch(page(5, 12, "c"), "");

        SearchStrategyProfile progressive = new SearchStrategyProfile("PROGRESSIVE_TEST",
                SearchStrategyProfile.ResultAcquisition.SERP_ONLY, 5,
                SearchStrategyProfile.AcquisitionOrder.PROGRESSIVE,
                SearchStrategyProfile.CandidateSelection.TOP_RANKED, 0, 0,
                SearchStrategyProfile.ObstaclePolicy.SKIP, SearchStrategyProfile.LinkExpansion.NONE, 0,
                SearchStrategyProfile.ProviderPolicy.DUCKDUCKGO_ONLY);

        SearchRun run = new SearchRunExecutor(discovery).execute("run-3", "q", "de", null,
                progressive, RUNNING, OPEN);

        assertEquals(3, discovery.calls);
        assertEquals(16, run.getCandidates().size());
        assertEquals(SearchRun.StopReason.SUFFICIENT, run.getStopReason());
    }

    @Test
    public void aLateBatchFailureKeepsEverythingTheEarlierBatchesProduced() throws Exception {
        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(page(1, 15, "a"), "page=2")
                .batch(page(16, 13, "b"), "page=3")
                .failingBatch();

        SearchRun run = new SearchRunExecutor(discovery).execute("run-4", "q", "de", null,
                SearchStrategyProfile.orientationSerpScan(), RUNNING, OPEN);

        assertEquals("28 usable hits are not 'nothing found'", 28, run.getCandidates().size());
        assertEquals(SearchRun.Status.RESULTS, run.getStatus());
        assertEquals(SearchRun.StopReason.TECHNICAL_PROBLEM, run.getStopReason());
        assertTrue("results in hand, traversal cut short", run.isPartial());
        assertEquals("the failed batch contributed nothing and is not listed", 2, run.getBatches().size());
    }

    @Test
    public void aFirstBatchFailureIsATechnicalProblemForTheWholeRun() throws Exception {
        SearchRun run = new SearchRunExecutor(new ScriptedDiscovery().failingBatch())
                .execute("run-5", "q", "de", null, SearchStrategyProfile.orientationSerpScan(),
                        RUNNING, OPEN);

        assertEquals(SearchRun.Status.TECHNICAL_PROBLEM, run.getStatus());
        assertEquals(SearchRun.StopReason.TECHNICAL_PROBLEM, run.getStopReason());
        assertFalse("nothing was produced, so this is not partial success", run.isPartial());
    }

    @Test
    public void theSameHitInSeveralBatchesBecomesOneCandidateThatKeepsBothAppearances() throws Exception {
        List<SearchResultCandidate> first = new ArrayList<SearchResultCandidate>(page(1, 2, "a"));
        List<SearchResultCandidate> second = new ArrayList<SearchResultCandidate>(page(3, 2, "b"));
        second.add(hit("https://a1.org/a", "Treffer 1 erneut", "a1.org")); // the same page again

        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(first, "page=2")
                .batch(second, "");

        SearchRun run = new SearchRunExecutor(discovery).execute("run-6", "q", "de", null,
                SearchStrategyProfile.orientationSerpScan(), RUNNING, OPEN);

        assertEquals("found twice is one hit, not two", 4, run.getCandidates().size());
        SearchCandidate repeated = run.candidate("c1");
        assertEquals("https://a1.org/a", repeated.getNormalizedUrl());
        assertEquals("both appearances stay on record", 2, repeated.getOccurrences().size());
        assertEquals(1, repeated.getOccurrences().get(0).getBatchOrdinal());
        assertEquals(2, repeated.getOccurrences().get(1).getBatchOrdinal());
        assertEquals(SearchRun.StopReason.NO_CONTINUATION, run.getStopReason());
    }

    @Test
    public void aProviderThatCannotPaginateEndsTheTraversalHonestly() throws Exception {
        SearchStrategy singleShot = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request,
                                              CancellationSignal cancellation, SearchBudgetGate budget) {
                return new InitialSearchResult(page(1, 5, "a"), Collections.<String>emptyList(),
                        Collections.<SearchChallengeState>emptyList(), Collections.<String>emptyList());
            }
        };

        SearchRun run = new SearchRunExecutor(new SingleBatchDiscovery(singleShot, "duckduckgo"))
                .execute("run-7", "q", "de", null, SearchStrategyProfile.orientationSerpScan(),
                        RUNNING, OPEN);

        assertEquals("one batch, not the same query three times", 1, run.getBatches().size());
        assertEquals(5, run.getCandidates().size());
        assertEquals(SearchRun.StopReason.NO_CONTINUATION, run.getStopReason());
    }

    @Test
    public void cancellationStopsTraversalButKeepsWhatWasAlreadyFound() throws Exception {
        final boolean[] cancelled = {false};
        ScriptedDiscovery discovery = new ScriptedDiscovery()
                .batch(page(1, 15, "a"), "page=2")
                .batch(page(16, 15, "b"), "page=3");

        CancellationSignal signal = new CancellationSignal() {
            public boolean isCancelled() {
                boolean now = cancelled[0];
                cancelled[0] = true; // cancelled from the second check on
                return now;
            }
        };

        SearchRun run = new SearchRunExecutor(discovery).execute("run-8", "q", "de", null,
                SearchStrategyProfile.orientationSerpScan(), signal, OPEN);

        assertEquals(1, discovery.calls);
        assertEquals(15, run.getCandidates().size());
        assertEquals(SearchRun.StopReason.CANCELLED, run.getStopReason());
        assertTrue(run.isPartial());
    }
}
