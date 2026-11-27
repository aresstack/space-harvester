package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The regression behind "…41 links assessed → 0 followed (floor 0.122…)": a single-candidate SERP made
 * the link floor equal to the run's BEST (and only) score, so no link could ever clear it, the frontier
 * starved, and the search ended right after its first page while reporting a normal completion. A floor
 * is the lower end of a range — one sample has no range, so one sample yields NO floor.
 */
public class SeedRelevanceFloorTest {

    private static RerankedSearchResultCandidate scored(double score, int rank) {
        return new RerankedSearchResultCandidate(null, score, rank);
    }

    @Test
    public void aSingleSelectedCandidateYieldsNoFloor() {
        assertNull(WebSearchApplicationService.seedFloorOf(
                Collections.singletonList(scored(0.122, 1))));
    }

    @Test
    public void anEmptyOrAbsentSelectionYieldsNoFloor() {
        assertNull(WebSearchApplicationService.seedFloorOf(
                Collections.<RerankedSearchResultCandidate>emptyList()));
        assertNull(WebSearchApplicationService.seedFloorOf(null));
    }

    @Test
    public void twoOrMoreCandidatesFloorAtTheirMinimum() {
        assertEquals(Double.valueOf(-0.1036), WebSearchApplicationService.seedFloorOf(
                Arrays.asList(scored(0.35, 1), scored(-0.1036, 2))));
        assertEquals(Double.valueOf(0.01), WebSearchApplicationService.seedFloorOf(
                Arrays.asList(scored(0.2, 1), scored(0.01, 2), scored(0.15, 3))));
    }
}
