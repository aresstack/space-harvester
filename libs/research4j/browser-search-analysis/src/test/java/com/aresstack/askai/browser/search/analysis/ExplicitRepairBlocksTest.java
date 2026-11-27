package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The live loop this closes:
 * <pre>
 * model:     "container-0114 is a search result."
 * validator: "then also name its parent."
 * model:     "container-0114 is a search result."   × 3
 * </pre>
 * and even had the model added the parent on a fourth attempt, the extractor would have thrown the
 * named block away and asked the mechanical detector to rediscover it — which then failed the page for
 * not having three similar siblings. A repair that identifies the result and is ignored twice over is
 * not a repair.
 */
public class ExplicitRepairBlocksTest {

    private static final LegacyBrowserSearchSettings SETTINGS =
            LayoutTestSupport.withoutLinkHarvest(LegacyBrowserSearchDefaults.create());

    /** The live shape: a region holding fewer repeated cards than the discovery heuristic demands. */
    private static RenderedPageDocument tooFewRepeatedCards(String[] outRegionId) {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(6);
        outRegionId[0] = serp.addResultColumn(2, new RenderedBox(300, 120, 680, 560),
                SerpDocuments.WHITE);
        return serp.build();
    }

    private static ValidatedSearchPageLayoutDecision decision(RenderedPageDocument document,
                                                              String region, List<String> blocks) {
        return new ValidatedSearchPageLayoutDecision("analysis-x", document.snapshotId, 0L, "", "",
                region, Arrays.asList(region), blocks, Collections.<String>emptyList(), 0.9);
    }

    /**
     * The control: the SAME fixture, with the repair naming no blocks, still fails. Without this the
     * test below could pass for the wrong reason — because the mechanical heuristic quietly coped.
     */
    @Test
    public void withoutNamedBlocksTheSameFixtureStillFails() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);

        SearchResultExtractionResult result = new LegacySearchResultExtractor(SETTINGS)
                .extract(document, decision(document, region[0], Collections.<String>emptyList()));

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
    }

    @Test
    public void aNamedResultBlockBecomesACandidate() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);
        String card = document.container(region[0]).childContainerIds.get(0);

        SearchResultExtractionResult result = new LegacySearchResultExtractor(SETTINGS)
                .extract(document, decision(document, region[0], Arrays.asList(card)));

        assertEquals("the repair named the card; repetition is no longer asked about",
                SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(1, result.candidates.size());
        assertEquals("Result 0 title", result.candidates.get(0).title);
        assertTrue("the primary link is resolved exactly as on the mechanical path",
                result.candidates.get(0).resolvedTargetUrl.startsWith("https://site0."));
        assertFalse(result.candidates.get(0).snippet.isEmpty());
    }

    /** Naming a container does not make it a result: without a qualifying link there is no candidate. */
    @Test
    public void aNamedBlockWithoutAQualifyingPrimaryLinkYieldsNoCandidate() {
        SerpDocuments serp = SerpDocuments.builder();
        String nav = serp.addNavigationBar(6);
        String region = serp.addResultColumn(2, new RenderedBox(300, 120, 680, 560),
                SerpDocuments.WHITE);
        RenderedPageDocument document = serp.build();

        SearchResultExtractionResult result = new LegacySearchResultExtractor(SETTINGS)
                .extract(document, decision(document, region, Arrays.asList(nav)));

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
    }

    /** A block id this snapshot does not know is refused — the strict id invariant is untouched. */
    @Test
    public void anUnknownBlockIdIsRefused() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);

        SearchResultExtractionResult result = new LegacySearchResultExtractor(SETTINGS)
                .extract(document, decision(document, region[0], Arrays.asList("container-9999")));

        assertEquals(SearchPageAnalysisOutcome.EXTRACTION_FAILED, result.outcome);
    }

    // ------------------------------------------------------------------ the parent is not a question

    private static SearchPageAnalysisArtifact artifactOf(RenderedPageDocument document) {
        return new SearchPageAnalysisArtifactBuilder(SETTINGS).build(document,
                new SearchPageMechanicalAnalyzer(SETTINGS).analyze(document), "hasensteaks");
    }

    private static SearchPageLayoutResolutionDecision raw(SearchPageAnalysisArtifact artifact,
                                                          List<String> organic, List<String> blocks,
                                                          List<String> excluded) {
        return new SearchPageLayoutResolutionDecision(artifact.analysisId, artifact.snapshotId,
                organic, blocks, excluded, 0.9, "x");
    }

    @Test
    public void theParentOfANamedBlockIsDerivedInsteadOfDemanded() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);
        String card = document.container(region[0]).childContainerIds.get(0);
        SearchPageAnalysisArtifact artifact = artifactOf(document);
        SearchPageLayoutDecisionValidator validator =
                new SearchPageLayoutDecisionValidator(SETTINGS.extraction);

        // The model names ONLY the card — exactly what it did live, three times.
        SearchPageLayoutResolutionDecision normalized = validator.normalize(
                raw(artifact, Collections.<String>emptyList(), Arrays.asList(card),
                        Collections.<String>emptyList()), artifact);
        SearchPageLayoutValidationResult validation = validator.validate(normalized, artifact);

        assertTrue("the DOM answers where the card sits; the model must not be failed for it",
                validation.valid);
        assertEquals("and the derived parent leads the organic regions",
                region[0], normalized.organicResultContainerIds.get(0));
    }

    /**
     * A region the model itself ruled out is not promoted — that would overrule its judgement. But the
     * named card survives: the region was never what made it a result.
     */
    @Test
    public void anExcludedParentIsNeverPromotedAndTheBlockStillStands() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);
        String card = document.container(region[0]).childContainerIds.get(0);
        SearchPageAnalysisArtifact artifact = artifactOf(document);
        SearchPageLayoutDecisionValidator validator =
                new SearchPageLayoutDecisionValidator(SETTINGS.extraction);

        SearchPageLayoutResolutionDecision normalized = validator.normalize(
                raw(artifact, Collections.<String>emptyList(), Arrays.asList(card),
                        Arrays.asList(region[0])), artifact);

        assertTrue("nothing was promoted", normalized.organicResultContainerIds.isEmpty());
        assertTrue("and the card is still a card — the extraction decides, not the region",
                validator.validate(normalized, artifact).valid);
    }

    /**
     * The whole live chain in one test: the mechanics fail on this page, the repair names the card
     * WITHOUT naming a region, validation accepts it, and the extraction produces the candidate.
     */
    @Test
    public void aNamedCardWithoutAnyRegionSurvivesValidationAndExtraction() {
        String[] region = new String[1];
        RenderedPageDocument document = tooFewRepeatedCards(region);
        String card = document.container(region[0]).childContainerIds.get(0);
        SearchPageAnalysisArtifact artifact = artifactOf(document);
        SearchPageLayoutDecisionValidator validator =
                new SearchPageLayoutDecisionValidator(SETTINGS.extraction);
        SearchPageLayoutResolutionDecision named = raw(artifact, Collections.<String>emptyList(),
                Arrays.asList(card), Collections.<String>emptyList());

        assertTrue("naming the card is an answer on its own",
                validator.validate(named, artifact).valid);

        ValidatedSearchPageLayoutDecision validated = validator.toValidatedDecision(named, artifact);
        assertEquals("with no region named, the card stands for itself as extraction context",
                card, validated.primaryOrganicContainerId);

        SearchResultExtractionResult result =
                new LegacySearchResultExtractor(SETTINGS).extract(document, validated);
        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, result.outcome);
        assertEquals(1, result.candidates.size());
    }
}
