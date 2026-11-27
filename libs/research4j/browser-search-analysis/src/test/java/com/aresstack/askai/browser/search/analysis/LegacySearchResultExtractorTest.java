package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The honest outcome semantics (A3d): validated blocks → ORGANIC_RESULTS with typed candidates;
 * an explicit no-results page → NO_ORGANIC_RESULTS; an ununderstood or broken layout →
 * EXTRACTION_FAILED (never "empty engine", never a flat link list); stale container references
 * are rejected by the snapshot guard.
 */
public class LegacySearchResultExtractorTest {

    private final LegacySearchResultExtractor extractor =
            new LegacySearchResultExtractor(LegacyBrowserSearchDefaults.create());

    @Test
    public void validatedBlocksBecomeTypedCandidates() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        SearchResultExtractionResult result = extractor.extract(serp.build());

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(3, result.candidates.size());
        SearchResultCandidate first = result.candidates.get(0);
        assertEquals(1, first.originalRank);
        assertEquals("Result 0 title", first.title);
        assertEquals("https://site0.example.org/page", first.resolvedTargetUrl);
        assertTrue(first.snippet.contains("Snippet for result 0"));
        assertTrue(first.structuralConfidence > 0.9);
        assertFalse(first.snapshotId.isEmpty());
        assertFalse(first.resultBlockContainerId.isEmpty());
    }

    @Test
    public void unresolvedRedirectHitsBecomeUnverifiedCandidatesWithTheirProviderUrl() {
        // askai #41 for the browser path (Bing/DuckDuckGo/Google): same separation as the Serp4J path.
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        serp.addBareResultColumn(4, new RenderedBox(300, 120, 680, 560),
                new boolean[] {false, true, false, true});
        SearchResultExtractionResult result = extractor.extract(serp.build());

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals("no hit is lost, two unresolved hits are not one duplicate", 4, result.candidates.size());
        SearchResultCandidate direct = result.candidates.get(0);
        assertEquals(UrlResolutionStatus.DIRECT, direct.urlResolution);
        assertTrue(direct.hasVerifiedTarget());
        assertEquals(direct.resolvedTargetUrl, direct.navigationUrl());
        for (int i : new int[] {1, 3}) {
            SearchResultCandidate unresolved = result.candidates.get(i);
            assertEquals(UrlResolutionStatus.UNRESOLVED, unresolved.urlResolution);
            assertFalse("never a verified target", unresolved.hasVerifiedTarget());
            assertEquals("", unresolved.resolvedTargetUrl);
            assertTrue(unresolved.rawSearchHref.contains("/goto?url="));
            assertEquals("the browser opens the provider URL", unresolved.rawSearchHref, unresolved.navigationUrl());
            assertEquals("provenance", unresolved.rawSearchHref, unresolved.originalUrl);
        }
        // The status survives the JSON hop between browser runtime and research runtime.
        SearchResultCandidate decoded = SearchLayoutRepairJson.decodePrepared(SearchLayoutRepairJson.encodePrepared(
                new com.aresstack.askai.browser.search.repair.PreparedWebSearchResult(
                        com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus.ORGANIC_RESULTS,
                        result.candidates,
                        Collections.<com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest>emptyList(),
                        Collections.singletonList("engine.example"),
                        Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                        Collections.<String>emptyList()))).candidates.get(1);
        assertEquals(UrlResolutionStatus.UNRESOLVED, decoded.urlResolution);
        assertEquals(result.candidates.get(1).navigationUrl(), decoded.navigationUrl());
        assertEquals(result.candidates.get(1).originalUrl, decoded.originalUrl);
    }

    @Test
    public void discardedEngineWrappersAreNeverCandidates() {
        // A Baidu /link wrapper without its result block's target: nothing resolves it later, so it must not
        // become a hit that makes the engine look delivered.
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        serp.addBareResultColumn(4, new RenderedBox(300, 120, 680, 560), new boolean[4],
                new boolean[] {false, true, false, true});
        SearchResultExtractionResult result = extractor.extract(serp.build());
        for (SearchResultCandidate candidate : result.candidates) {
            assertFalse(candidate.navigationUrl(), candidate.navigationUrl().contains("baidu.com/link"));
            assertFalse(candidate.rawSearchHref, candidate.rawSearchHref.contains("baidu.com/link"));
        }
        assertEquals(2, result.candidates.size());
    }

    @Test
    public void explicitNoResultsPageIsNoOrganicResultsNotAFailure() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        serp.addPlainContainer("div", "message", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(300, 200, 680, 80),
                60, 0, 0, 0, 0);
        // The plain container carries the generic text; override via a dedicated fixture entry is
        // not needed — the marker only has to appear in ANY captured excerpt.
        SearchResultExtractionResult result = extractor.extract(
                withExcerpt(serp.build(), "Keine Ergebnisse für diese Suche gefunden."));
        assertEquals(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS, result.outcome);
        assertTrue(result.candidates.isEmpty());
    }

    /**
     * Bing's English header "About 1,230,000 results" ends in the configured marker "0 results". It is a
     * result COUNT, not a no-results statement: an ununderstood page carrying it stays EXTRACTION_FAILED
     * (the link harvest / AI repair still get their chance) instead of a false "genuinely empty" verdict
     * that opens no hit at all.
     */
    @Test
    public void aResultCountEndingInZeroResultsIsNotANoResultsPage() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        serp.addPlainContainer("div", "message", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(300, 200, 680, 80),
                60, 0, 0, 0, 0);
        SearchResultExtractionResult counted = extractor.extract(
                withExcerpt(serp.build(), "About 1,230,000 results"));
        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, counted.outcome);

        SearchResultExtractionResult empty = extractor.extract(
                withExcerpt(serp.build(), "0 results for this search"));
        assertEquals(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS, empty.outcome);
    }

    @Test
    public void aDigitMarkerOnlyMatchesAsAWholeNumber() {
        assertFalse(LegacySearchResultExtractor.containsNoResultsMarker("about 1,230,000 results", "0 results"));
        assertFalse(LegacySearchResultExtractor.containsNoResultsMarker("showing 10 results", "0 results"));
        assertFalse(LegacySearchResultExtractor.containsNoResultsMarker("1 230 000 results", "0 results"));
        assertTrue(LegacySearchResultExtractor.containsNoResultsMarker("0 results", "0 results"));
        assertTrue(LegacySearchResultExtractor.containsNoResultsMarker("found 0 results", "0 results"));
        // Later occurrence after a rejected one (a count first, the statement afterwards).
        assertTrue(LegacySearchResultExtractor.containsNoResultsMarker(
                "20 results overall. 0 results on this page", "0 results"));
        // Markers without a leading digit keep their plain substring semantics.
        assertTrue(LegacySearchResultExtractor.containsNoResultsMarker("leider keine treffer.", "keine treffer"));
        assertFalse(LegacySearchResultExtractor.containsNoResultsMarker("anything", ""));
    }

    @Test
    public void anUnunderstoodLayoutIsAnExtractionFailureNotAnEmptyEngine() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addPlainContainer("div", "", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 1280, 2000),
                40, 0, 0, 0, 0);
        SearchResultExtractionResult result = extractor.extract(serp.build());
        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
        assertTrue(result.candidates.isEmpty());
        assertTrue("diagnostics must explain the failure",
                describe(result).contains("LOW_CONFIDENCE"));
    }

    @Test
    public void staleResolutionAgainstAnotherSnapshotIsRejected() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        RenderedPageDocument document = serp.build();
        SearchPageLayoutResolution resolution = new SearchPageMechanicalAnalyzer(
                LegacyBrowserSearchDefaults.create()).analyze(document);

        SerpDocuments other = SerpDocuments.builder();
        other.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        RenderedPageDocument otherDocument = withSnapshotId(other.build(), "snap-2-other");
        SearchResultBlockDetector.Detection detection = new SearchResultBlockDetector(
                LegacyBrowserSearchDefaults.create()).detect(otherDocument, resolution);
        assertTrue(detection.blocks.isEmpty());
        assertTrue(detection.rejectionReasons.get(0).contains("stale"));
    }

    // ------------------------------------------------------------------ fixture surgery

    /** Rebuild the document with one extra container carrying the given text excerpt. */
    private static RenderedPageDocument withExcerpt(RenderedPageDocument document, String excerpt) {
        java.util.List<com.aresstack.askai.browser.render.RenderedContainerDescriptor> containers =
                new java.util.ArrayList<com.aresstack.askai.browser.render
                        .RenderedContainerDescriptor>(document.containers);
        containers.add(com.aresstack.askai.browser.render.RenderedContainerDescriptor
                .builder("container-9999")
                .hierarchy("container-0001", Collections.<String>emptyList(), 9, 1)
                .semantics("div", "no-results", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text(excerpt, excerpt.length(), 0, excerpt.length(), 0, 1)
                .links(0, 0, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(300, 200, 680, 60), 1.0, false, 0.1, 0.2)
                .colors(SerpDocuments.WHITE, SerpDocuments.WHITE, 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new com.aresstack.askai.browser.render.DomStructureSignature("div"), 0)
                .build());
        return new RenderedPageDocument(document.snapshotId, document.snapshotGeneration,
                document.pageUrl, document.pageTitle, document.viewport,
                document.documentFingerprint, document.rootContainerIds, containers,
                document.links, document.captureTruncated, document.captureWarnings);
    }

    private static RenderedPageDocument withSnapshotId(RenderedPageDocument document, String id) {
        return new RenderedPageDocument(id, document.snapshotGeneration + 1, document.pageUrl,
                document.pageTitle, document.viewport, document.documentFingerprint,
                document.rootContainerIds, document.containers, document.links,
                document.captureTruncated, document.captureWarnings);
    }

    private static String describe(SearchResultExtractionResult result) {
        StringBuilder sb = new StringBuilder();
        for (String line : result.diagnostics) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
