package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The live case behind this test: a Bing result page a human could read — one visibly useful hit — ended
 * the whole search technically.
 * <pre>
 * prepare status=FAILED candidates=0 repairTickets=0
 * "a result region was resolved but no valid result block emerged"
 * </pre>
 * The mechanics were sure enough about the layout to skip the repair, and then could not read a single
 * result block out of the region they were sure about. Those two statements contradict each other, and a
 * contradiction is not a verdict — it is precisely the situation the layout repair exists for.
 */
public class ConfidentButUnreadableLayoutTest {

    private static final LegacyBrowserSearchSettings SETTINGS =
            LayoutTestSupport.withoutLinkHarvest(LegacyBrowserSearchDefaults.create());

    /** A result region the mechanics accept, holding fewer repeated blocks than the detector demands. */
    private static RenderedPageDocument regionWithoutARepeatedCluster() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(4);
        serp.addResultColumn(2, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        return serp.build();
    }

    @Test
    public void aRegionTheMechanicsBelieveButCannotReadBecomesARepairRequest() {
        RenderedPageDocument document = regionWithoutARepeatedCluster();

        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(SETTINGS).analyze(document);
        assertFalse("the fixture must be the CONFIDENT case, otherwise this proves nothing",
                resolution.lowConfidence);
        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(SETTINGS).extract(document, "hasensteaks");
        assertEquals("and the extraction must genuinely fail on it",
                SearchPageAnalysisOutcome.EXTRACTION_FAILED, extraction.outcome);

        PreparedWebSearchResult prepared = new WebSearchLayoutRepairService(SETTINGS, 4, 10_000L)
                .prepareSingle(document, "hasensteaks", "www.bing.com", 1_000L);

        assertEquals("a page nobody could read is not a finished page",
                WebSearchPreparationStatus.REPAIR_REQUIRED, prepared.status);
        assertEquals("and it is offered for exactly one repair", 1, prepared.repairRequests.size());
    }

    /** A readable page still answers directly: the repair is for failure, not for every page. */
    @Test
    public void areadablePageStillNeedsNoRepair() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(4);
        serp.addResultColumn(5, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);

        PreparedWebSearchResult prepared = new WebSearchLayoutRepairService(SETTINGS, 4, 10_000L)
                .prepareSingle(serp.build(), "hasensteaks", "www.bing.com", 1_000L);

        assertEquals(WebSearchPreparationStatus.ORGANIC_RESULTS, prepared.status);
        assertTrue(prepared.repairRequests.isEmpty());
        assertFalse(prepared.candidates.isEmpty());
    }
}
