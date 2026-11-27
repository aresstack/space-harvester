package com.aresstack.askai.research.domain.search;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Discovery is a RESULT, not a step towards opening pages, and a hit is an immutable fact rather than a
 * state machine. Both are pinned here: a run that read nothing is successful, and what happened to a page
 * lives in inspection attempts instead of rewriting the hit.
 */
public class SearchRunTest {

    private static SearchCandidate candidate(int index, String host, int batch, int rank) {
        return new SearchCandidate("c" + index, "https://" + host + "/page" + index, "Titel " + index,
                "Snippet " + index, host,
                Arrays.asList(new SearchOccurrence("duckduckgo", batch, rank,
                        "https://" + host + "/page" + index + "?utm=x")));
    }

    private static SearchRun run(int candidateCount, int batchCount) {
        List<SearchCandidate> candidates = new ArrayList<SearchCandidate>();
        for (int index = 1; index <= candidateCount; index++) {
            candidates.add(candidate(index, "example" + (index % 7) + ".org",
                    1 + (index % batchCount), index));
        }
        List<DiscoveryBatch> batches = new ArrayList<DiscoveryBatch>();
        for (int ordinal = 1; ordinal <= batchCount; ordinal++) {
            batches.add(new DiscoveryBatch(ordinal, "duckduckgo", 15,
                    ordinal < batchCount ? "page=" + (ordinal + 1) : ""));
        }
        return SearchRun.discovered("run-1", "truthahnragout tradition", "ORIENTATION_SERP_SCAN",
                SearchRun.Status.RESULTS, SearchRun.StopReason.BATCH_LIMIT_REACHED, batches, candidates);
    }

    @Test
    public void aRunWithManyCandidatesAndZeroVisitsIsSuccessfulDiscovery() {
        SearchRun discovery = run(42, 3);

        assertEquals(SearchRun.Status.RESULTS, discovery.getStatus());
        assertEquals(42, discovery.getCandidates().size());
        assertEquals(3, discovery.getBatches().size());
        assertEquals("reading nothing is a valid outcome, not a failure", 0, discovery.readCount());
        assertEquals("run=run-1 status=RESULTS stop=BATCH_LIMIT_REACHED batches=3 candidates=42 read=0",
                discovery.describe());
        assertFalse("a clean traversal is not a partial run", discovery.isPartial());
        assertEquals("a hit nobody looked at has no inspection at all",
                null, discovery.latestInspection("c5"));
    }

    /** A hit that was never looked at is simply a hit — not a skipped one. */
    @Test
    public void aCandidateCarriesNoInspectionStateOfItsOwn() {
        SearchRun discovery = run(3, 1);
        for (SearchCandidate candidate : discovery.getCandidates()) {
            for (java.lang.reflect.Method method : candidate.getClass().getMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name, name.contains("status") || name.contains("inspect")
                        || name.contains("skip"));
            }
        }
    }

    @Test
    public void theSameHitCanBeSkippedTodayAndReadTomorrowWithBothAttemptsOnRecord() {
        SearchRun discovery = run(5, 1)
                .withInspection(new InspectionAttempt("c2", 1000L, "ORIENTATION_SERP_SCAN",
                        InspectionAttempt.Outcome.SKIPPED, "", "no visits in this profile"))
                .withInspection(new InspectionAttempt("c2", 2000L, "STANDARD_RESEARCH",
                        InspectionAttempt.Outcome.READ, "source-731", ""));

        assertEquals("both attempts stay on record", 2, discovery.getInspections().size());
        assertEquals(InspectionAttempt.Outcome.READ, discovery.latestInspection("c2").getOutcome());
        assertEquals("the candidate -> source link is traceable",
                "source-731", discovery.latestInspection("c2").getSourceId());
        assertEquals("the discovery record itself is untouched",
                "Titel 2", discovery.candidate("c2").getTitle());
        assertEquals(1, discovery.readCount());
    }

    @Test
    public void repeatedFailedAttemptsCountAsOneCandidateAndStayRetryable() {
        SearchRun discovery = run(3, 1)
                .withInspection(new InspectionAttempt("c1", 1L, "QUICK_ORIENTATION",
                        InspectionAttempt.Outcome.BLOCKED, "", "consent wall"))
                .withInspection(new InspectionAttempt("c1", 2L, "QUICK_ORIENTATION",
                        InspectionAttempt.Outcome.FAILED, "", "timeout"));

        assertEquals(0, discovery.readCount());
        assertTrue(discovery.latestInspection("c1").isRetryable());
        assertEquals("consent wall", discovery.getInspections().get(0).getDetail());
    }

    @Test
    public void oneHitFoundSeveralTimesIsOneCandidateThatKeepsEveryProvenance() {
        SearchCandidate found = candidate(18, "example.org", 1, 8)
                .withOccurrence(new SearchOccurrence("duckduckgo", 3, 2, "https://example.org/page18#x"))
                .withOccurrence(new SearchOccurrence("brave", 1, 5, "https://example.org/page18"));

        assertEquals("deduplication must not multiply the hit", 3, found.getOccurrences().size());
        assertEquals("https://example.org/page18", found.getNormalizedUrl());
        assertEquals("the earliest appearance is its natural order", 1, found.firstBatchOrdinal());
        assertEquals(2, found.bestRank());
        assertTrue("two engines returned it — visible, but never a score",
                found.foundBySeveralProviders());
    }

    @Test
    public void aCandidateStaysAddressableByItsIdRatherThanByItsUrl() {
        SearchRun discovery = run(20, 2);

        assertNotNull(discovery.candidate("c18"));
        assertEquals("https://example4.org/page18", discovery.candidate("c18").getNormalizedUrl());
        assertNull(discovery.candidate("nope"));
        assertNull(discovery.candidate(null));
    }

    @Test
    public void batchesCarryTheProviderContinuationInsteadOfPretendingToBePages() {
        SearchRun discovery = run(10, 3);

        assertEquals(Arrays.asList("duckduckgo"), discovery.providers());
        assertTrue(discovery.getBatches().get(0).hasContinuation());
        assertEquals("page=2", discovery.getBatches().get(0).getContinuation());
        assertFalse("the last batch offers no continuation",
                discovery.getBatches().get(2).hasContinuation());
    }

    @Test
    public void anEmptySearchAndATechnicalFailureAreDifferentOutcomes() {
        SearchRun empty = SearchRun.discovered("run-2", "q", "ORIENTATION_SERP_SCAN",
                SearchRun.Status.RESULTS, SearchRun.StopReason.NO_CONTINUATION,
                Collections.<DiscoveryBatch>emptyList(), Collections.<SearchCandidate>emptyList());
        assertEquals("no candidates means the search honestly found nothing",
                SearchRun.Status.NO_RESULTS, empty.getStatus());

        SearchRun broken = SearchRun.discovered("run-3", "q", "ORIENTATION_SERP_SCAN",
                SearchRun.Status.TECHNICAL_PROBLEM, SearchRun.StopReason.TECHNICAL_PROBLEM,
                Collections.<DiscoveryBatch>emptyList(), Collections.<SearchCandidate>emptyList());
        assertEquals("a search that could not run must stay distinguishable from an empty one",
                SearchRun.Status.TECHNICAL_PROBLEM, broken.getStatus());
    }
}
