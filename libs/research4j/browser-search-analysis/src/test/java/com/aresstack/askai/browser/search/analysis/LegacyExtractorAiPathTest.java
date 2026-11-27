package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A4d: the honest success matrix for applying AI layout resolution to extraction.
 * HIGH_CONFIDENCE never calls the model; disabled/unavailable AI stays EXTRACTION_FAILED; a valid AI
 * (or hand-validated) decision feeds the EXISTING block extraction and yields real candidates; a
 * valid decision with no result blocks is EXTRACTION_FAILED, never NO_ORGANIC_RESULTS; an explicit
 * no-results page is NO_ORGANIC_RESULTS without ever calling the model; a stale decision is refused.
 */
public class LegacyExtractorAiPathTest {

    private final LegacyBrowserSearchSettings defaults =
            LayoutTestSupport.withoutLinkHarvest(LegacyBrowserSearchDefaults.create());

    private RenderedPageDocument resultColumnDocument(String[] outColumnId) {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        String col = serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        if (outColumnId != null) {
            outColumnId[0] = col;
        }
        return serp.build();
    }

    private String response(String snapshotId, String organicId) {
        return "{\"analysisId\":\"analysis-" + snapshotId + "-1\",\"snapshotId\":\"" + snapshotId + "\","
                + "\"organicResultContainerIds\":[\"" + organicId + "\"],"
                + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"x\"}";
    }

    private LegacySearchResultExtractor wired(LegacyBrowserSearchSettings settings,
                                              ScriptedStructuredInferencePort port) {
        return new LegacySearchResultExtractor(settings,
                new AiSearchPageLayoutResolver(port, settings.extraction), CancellationSignal.NONE);
    }

    @Test
    public void highConfidenceNeverCallsTheModelAndKeepsCandidates() {
        LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(defaults,
                LayoutTestSupport.aiSettings(true, "p", LayoutTestSupport.retryPolicy(3)));
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        SearchResultExtractionResult result =
                wired(settings, port).extract(resultColumnDocument(null), "q");

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(3, result.candidates.size());
        assertEquals("high-confidence page must never call the model", 0, port.callCount());
    }

    @Test
    public void lowConfidenceWithDisabledAiFailsWithoutCallingModel() {
        LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(
                LayoutTestSupport.forcingLowConfidence(defaults),
                LayoutTestSupport.aiSettings(false, "", LayoutTestSupport.retryPolicy(3)));
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        SearchResultExtractionResult result =
                wired(settings, port).extract(resultColumnDocument(null), "q");

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
        assertEquals(0, port.callCount());
    }

    @Test
    public void lowConfidenceWithUnavailableAiFailsTyped() {
        LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(
                LayoutTestSupport.forcingLowConfidence(defaults),
                LayoutTestSupport.aiSettings(true, "p", LayoutTestSupport.retryPolicy(3)));
        LegacySearchResultExtractor extractor = new LegacySearchResultExtractor(settings,
                new AiSearchPageLayoutResolver(new UnavailableStructuredInferencePort(),
                        settings.extraction), CancellationSignal.NONE);
        SearchResultExtractionResult result = extractor.extract(resultColumnDocument(null), "q");

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
        assertTrue(diagnostics(result).contains("AI_UNAVAILABLE"));
    }

    @Test
    public void lowConfidenceWithValidAiResolutionYieldsRealCandidates() {
        LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(
                LayoutTestSupport.forcingLowConfidence(defaults),
                LayoutTestSupport.aiSettings(true, "p", LayoutTestSupport.retryPolicy(3)));
        String[] col = new String[1];
        RenderedPageDocument document = resultColumnDocument(col);
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response(document.snapshotId, col[0]));
        SearchResultExtractionResult result = wired(settings, port).extract(document, "berlin");

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(3, result.candidates.size());
        assertEquals(1, port.callCount());
        assertEquals("Result 0 title", result.candidates.get(0).title);
        assertTrue(result.candidates.get(0).snippet.contains("Snippet for result 0"));
    }

    @Test
    public void validDecisionApplyingThroughOverloadProducesTypedCandidates() {
        String[] col = new String[1];
        RenderedPageDocument document = resultColumnDocument(col);
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                "analysis-x", document.snapshotId, 0L, "", "", col[0], Arrays.asList(col[0]),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9);
        SearchResultExtractionResult result =
                new LegacySearchResultExtractor(defaults).extract(document, decision);

        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(3, result.candidates.size());
    }

    @Test
    public void validDecisionWithNoBlocksIsExtractionFailedNotNoOrganicResults() {
        SerpDocuments serp = SerpDocuments.builder();
        String plain = serp.addPlainContainer("div", "panel", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(300, 120, 680, 300), 200, 40, 3, 0,
                3);
        RenderedPageDocument document = serp.build();
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                "analysis-x", document.snapshotId, 0L, "", "", plain, Arrays.asList(plain),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9);
        SearchResultExtractionResult result =
                new LegacySearchResultExtractor(defaults).extract(document, decision);

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
    }

    @Test
    public void explicitNoResultsIsNoOrganicResultsWithoutCallingModel() {
        LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(
                LayoutTestSupport.forcingLowConfidence(defaults),
                LayoutTestSupport.aiSettings(true, "p", LayoutTestSupport.retryPolicy(3)));
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        RenderedPageDocument document =
                withExcerpt(serp.build(), "Keine Ergebnisse für diese Suche gefunden.");
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        SearchResultExtractionResult result = wired(settings, port).extract(document, "q");

        assertEquals(SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS, result.outcome);
        assertEquals("an explicit no-results page must not call the model", 0, port.callCount());
    }

    @Test
    public void staleDecisionIsRefused() {
        RenderedPageDocument document = resultColumnDocument(new String[1]);
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                "analysis-x", "snap-OTHER", 0L, "", "", "container-0003",
                Arrays.asList("container-0003"),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9);
        SearchResultExtractionResult result =
                new LegacySearchResultExtractor(defaults).extract(document, decision);

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
        assertTrue(diagnostics(result).contains("stale"));
    }

    private static String diagnostics(SearchResultExtractionResult result) {
        StringBuilder sb = new StringBuilder();
        for (String line : result.diagnostics) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Append one container carrying the given excerpt (an explicit no-results marker). */
    private static RenderedPageDocument withExcerpt(RenderedPageDocument document, String excerpt) {
        List<RenderedContainerDescriptor> containers =
                new ArrayList<RenderedContainerDescriptor>(document.containers);
        containers.add(RenderedContainerDescriptor.builder("container-9999")
                .hierarchy("container-0001", Collections.<String>emptyList(), 9, 1)
                .semantics("div", "no-results", Collections.<String>emptyList(), "", "",
                        Collections.<String>emptyList())
                .text(excerpt, excerpt.length(), 0, excerpt.length(), 0, 1)
                .links(0, 0, 0, 0, 0, 0)
                .geometry(true, new RenderedBox(300, 200, 680, 60), 1.0, false, 0.1, 0.2)
                .colors(SerpDocuments.WHITE, SerpDocuments.WHITE, 0, 0)
                .separation("", 0, "", 0, 0)
                .structure(new DomStructureSignature("div"), 0)
                .build());
        return new RenderedPageDocument(document.snapshotId, document.snapshotGeneration,
                document.pageUrl, document.pageTitle, document.viewport,
                document.documentFingerprint, document.rootContainerIds, containers,
                document.links, document.captureTruncated, document.captureWarnings);
    }
}
