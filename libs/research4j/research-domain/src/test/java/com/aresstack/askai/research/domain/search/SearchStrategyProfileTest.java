package com.aresstack.askai.research.domain.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The profile is what makes "how deep do we search" a decision instead of a set of constants scattered
 * through the code. Two properties matter most, both from real runs: a short orientation can never end up
 * asking the user to click through a foreign website, and "visit three pages" means three pages READ.
 */
public class SearchStrategyProfileTest {

    @Test
    public void anOrientationScanOpensNothingAndThereforeCanNeverWaitForTheUser() {
        SearchStrategyProfile scan = SearchStrategyProfile.orientationSerpScan();

        assertTrue(scan.isSerpOnly());
        assertEquals(0, scan.getTargetSuccessfulVisits());
        assertEquals("no visit budget may linger on a scan profile", 0, scan.getMaxVisitAttempts());
        assertEquals(SearchStrategyProfile.LinkExpansion.NONE, scan.getLinkExpansion());
        assertEquals(0, scan.getMaxExpandedLinks());
        assertEquals(SearchStrategyProfile.ObstaclePolicy.SKIP, scan.getObstaclePolicy());
        assertFalse(scan.mayWaitForUser());
        assertEquals("orientation must not let page 1 decide the map",
                SearchStrategyProfile.AcquisitionOrder.COLLECT_THEN_SELECT, scan.getAcquisitionOrder());
        assertEquals(3, scan.getMaxDiscoveryBatches());
        assertEquals(SearchStrategyProfile.ProviderPolicy.DUCKDUCKGO_ONLY, scan.getProviderPolicy());
    }

    @Test
    public void attemptsMayExceedTheTargetSoSkippedPagesDoNotEatTheBudget() {
        SearchStrategyProfile quick = SearchStrategyProfile.quickOrientation();

        assertEquals(3, quick.getTargetSuccessfulVisits());
        assertTrue("two consent walls must not end a three-page read",
                quick.getMaxVisitAttempts() > quick.getTargetSuccessfulVisits());
        assertEquals("a short orientation still never chases links",
                SearchStrategyProfile.LinkExpansion.NONE, quick.getLinkExpansion());
        assertFalse(quick.mayWaitForUser());
    }

    @Test
    public void aTargetLargerThanTheAttemptBudgetIsCorrectedRatherThanSilentlyImpossible() {
        SearchStrategyProfile odd = new SearchStrategyProfile("odd",
                SearchStrategyProfile.ResultAcquisition.VISIT_RESULTS, 1,
                SearchStrategyProfile.AcquisitionOrder.PROGRESSIVE,
                SearchStrategyProfile.CandidateSelection.TOP_RANKED,
                2, 5, SearchStrategyProfile.ObstaclePolicy.SKIP,
                SearchStrategyProfile.LinkExpansion.NONE, 0,
                SearchStrategyProfile.ProviderPolicy.DEFAULT_CHAIN);

        assertEquals(5, odd.getTargetSuccessfulVisits());
        assertEquals("attempts can never be fewer than the pages we want to read",
                5, odd.getMaxVisitAttempts());
    }

    @Test
    public void linkExpansionIsItsOwnBudgetSoThreeVisitsCannotBecomeEighteen() {
        SearchStrategyProfile standard = SearchStrategyProfile.standardResearch();

        assertEquals(SearchStrategyProfile.LinkExpansion.LIMITED, standard.getLinkExpansion());
        assertEquals(10, standard.getMaxExpandedLinks());
        assertEquals(8, standard.getTargetSuccessfulVisits());
        assertEquals("obstacles are parked, not handed to the user",
                SearchStrategyProfile.ObstaclePolicy.DEFER, standard.getObstaclePolicy());
        assertFalse(standard.mayWaitForUser());
    }

    @Test
    public void onlyTheDeliberateLongRunMayAskTheUserToResolveAnObstacle() {
        assertTrue(SearchStrategyProfile.deepResearch().mayWaitForUser());
        assertEquals(SearchStrategyProfile.LinkExpansion.DEEP,
                SearchStrategyProfile.deepResearch().getLinkExpansion());
    }

    @Test
    public void aSerpOnlyProfileDropsAnyVisitOrExpansionBudgetItWasGiven() {
        // Stray numbers would invite a later code path to open "just one" page.
        SearchStrategyProfile contradictory = new SearchStrategyProfile("contradictory",
                SearchStrategyProfile.ResultAcquisition.SERP_ONLY, 2,
                SearchStrategyProfile.AcquisitionOrder.COLLECT_THEN_SELECT,
                SearchStrategyProfile.CandidateSelection.TOP_RANKED,
                50, 20, SearchStrategyProfile.ObstaclePolicy.WAIT_FOR_USER,
                SearchStrategyProfile.LinkExpansion.DEEP, 99,
                SearchStrategyProfile.ProviderPolicy.DEFAULT_CHAIN);

        assertEquals(0, contradictory.getMaxVisitAttempts());
        assertEquals(0, contradictory.getTargetSuccessfulVisits());
        assertEquals(0, contradictory.getMaxExpandedLinks());
        assertFalse("SERP_ONLY makes waiting for the user structurally impossible",
                contradictory.mayWaitForUser());
    }

    @Test
    public void theProfileDescribesItselfForTheRunLog() {
        assertTrue(SearchStrategyProfile.orientationSerpScan().describe()
                .contains("ORIENTATION_SERP_SCAN acquisition=SERP_ONLY batches<=3"));
    }
}
