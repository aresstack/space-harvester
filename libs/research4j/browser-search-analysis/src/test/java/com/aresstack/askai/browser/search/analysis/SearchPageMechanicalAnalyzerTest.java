package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The mechanical layout judgement: results beat navigation, the center prior never displaces a
 * left-aligned result list with strong structural signals, an everywhere-identical background is a
 * NEUTRAL color family, ad/pagination naming classifies without hitting look-alike words, and a
 * page without discriminating signals is LOW_CONFIDENCE — never a guessed container.
 */
public class SearchPageMechanicalAnalyzerTest {

    private final SearchPageMechanicalAnalyzer analyzer =
            new SearchPageMechanicalAnalyzer(LegacyBrowserSearchDefaults.create());

    @Test
    public void resultColumnWinsAgainstNavigationAndAuxiliaries() {
        SerpDocuments serp = SerpDocuments.builder();
        String nav = serp.addNavigationBar(9);
        String results = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        serp.addPlainContainer("div", "sidebar", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(1000, 120, 260, 500),
                200, 150, 12, 12, 0);
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertTrue(resolution.hasOrganicResultsContainer());
        assertEquals(results, resolution.organicResultsContainerId);
        assertFalse(resolution.lowConfidence);
        assertTrue("confidence must clear the configured minimum",
                resolution.confidence >= 0.5);
        assertEquals(SearchPageRegionClassification.NAVIGATION,
                resolution.regionByContainerId.get(nav));
        assertEquals(SearchPageRegionClassification.ORGANIC_RESULTS,
                resolution.regionByContainerId.get(results));
    }

    @Test
    public void theBodyRootIsNeverOfferedAsAResultRegion() {
        // The real capture aggregates the whole page into <body>. The decision validator refuses the
        // root as FULL_PAGE_SELECTION, so offering it let a small model pick it on every repair
        // attempt (live: three times container-0001 on Bing, then EXTRACTION_FAILED).
        SerpDocuments serp = SerpDocuments.builder().withAggregatingBody();
        serp.addNavigationBar(8);
        serp.addPlainContainer("div", "", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(300, 120, 680, 900),
                900, 300, 12, 0, 12);
        RenderedPageDocument document = serp.build();
        assertTrue("fixture: the body really carries links and text",
                document.container("container-0001").linkCount > 0);

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        for (HeuristicScoreBreakdown candidate : resolution.scoredCandidates) {
            assertFalse("the root must not be a candidate",
                    "container-0001".equals(candidate.containerId));
        }
        assertFalse("container-0001".equals(resolution.organicResultsContainerId));
    }

    @Test
    public void leftAlignedResultListBeatsTheCenterPrior() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        // Result column entirely LEFT of the center probe zone (probe starts at x=256).
        String results = serp.addResultColumn(3,
                new RenderedBox(0, 120, 240, 560), SerpDocuments.WHITE);
        // A centered but structurally weak panel competes via the center prior only.
        serp.addPlainContainer("div", "knowledge-panel", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(400, 200, 480, 400),
                160, 40, 2, 1, 1);
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertEquals("strong structural signals must beat the center prior",
                results, resolution.organicResultsContainerId);
        assertFalse(resolution.lowConfidence);
    }

    @Test
    public void monochromePageKeepsTheColorFamilyNeutral() {
        SerpDocuments serp = SerpDocuments.builder();
        String results = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE); // same as page bg
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertEquals(results, resolution.organicResultsContainerId);
        HeuristicScoreBreakdown best = resolution.scoredCandidates.get(0);
        for (HeuristicSignal signal : best.signals) {
            assertFalse("identical background must not emit a distinct-background signal",
                    signal.name.equals("distinctBackground"));
        }
    }

    @Test
    public void distinctBackgroundIsAPositiveSeparationSignal() {
        SerpDocuments serp = SerpDocuments.builder();
        // A clearly different content background against the white page.
        String results = serp.addResultColumn(3,
                new RenderedBox(300, 120, 680, 560), new com.aresstack.askai.browser.render
                        .RenderedColor(220, 235, 255, 1));
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertEquals(results, resolution.organicResultsContainerId);
        boolean distinct = false;
        for (HeuristicSignal signal : resolution.scoredCandidates.get(0).signals) {
            if (signal.name.equals("distinctBackground")) {
                assertTrue(signal.score > 0);
                distinct = true;
            }
        }
        assertTrue(distinct);
    }

    @Test
    public void pageWithoutDiscriminatingSignalsIsLowConfidenceNeverAGuess() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addPlainContainer("div", "", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 1280, 2000), 40, 0, 0, 0, 0);
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertTrue(resolution.lowConfidence);
        assertFalse(resolution.hasOrganicResultsContainer());
    }

    @Test
    public void namingClassifiesAdsPaginationAndFiltersWithoutFalseTokenMatches() {
        SerpDocuments serp = SerpDocuments.builder();
        String ad = serp.addPlainContainer("div", "", Arrays.asList("ad", "top"),
                Collections.<String>emptyList(), new RenderedBox(300, 60, 680, 90),
                120, 60, 2, 0, 2);
        String pagination = serp.addPlainContainer("div", "pagination",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                new RenderedBox(300, 900, 680, 40), 100, 90, 8, 8, 0);
        // Look-alike words must NOT classify: "header" contains "ad", "badge" contains "ad".
        String header = serp.addPlainContainer("div", "header", Arrays.asList("badge"),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 1280, 40),
                100, 20, 1, 1, 0);
        RenderedPageDocument document = serp.build();

        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        assertEquals(SearchPageRegionClassification.ADVERTISEMENT,
                resolution.regionByContainerId.get(ad));
        assertEquals(SearchPageRegionClassification.PAGINATION,
                resolution.regionByContainerId.get(pagination));
        assertEquals(SearchPageRegionClassification.UNKNOWN,
                resolution.regionByContainerId.get(header));
    }
}
