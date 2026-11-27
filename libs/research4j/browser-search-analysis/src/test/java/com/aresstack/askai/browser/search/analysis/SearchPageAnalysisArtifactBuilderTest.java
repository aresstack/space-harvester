package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.MechanicalConfidenceOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A4a: the neutral analysis artifact is BOUNDED and snapshot-bound. Text excerpts respect the
 * configured cap, container ids are the snapshot-local descriptors only, the mechanical verdict and
 * preferred ids are projected honestly, and the settings digest is stable — no secrets, no raw DOM.
 */
public class SearchPageAnalysisArtifactBuilderTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    private RenderedPageDocument highConfidenceDocument() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(9);
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        return serp.build();
    }

    @Test
    public void artifactIsBoundToSnapshotAndReportsHighConfidence() {
        RenderedPageDocument document = highConfidenceDocument();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);

        SearchPageAnalysisArtifact artifact =
                new SearchPageAnalysisArtifactBuilder(defaults).build(document, resolution, "berlin");

        assertEquals(document.snapshotId, artifact.snapshotId);
        assertEquals(document.snapshotGeneration, artifact.snapshotGeneration);
        assertEquals("f", artifact.documentFingerprint);
        assertTrue("analysisId must embed the snapshot id",
                artifact.analysisId.contains(document.snapshotId));
        assertEquals("berlin", artifact.searchQuery);
        assertEquals(MechanicalConfidenceOutcome.HIGH_CONFIDENCE, artifact.mechanicalOutcome);
        assertEquals(resolution.organicResultsContainerId,
                artifact.mechanicallyPreferredContainerIds.get(0));
        assertFalse(artifact.settingsDigest.isEmpty());
        assertEquals(EngineFamily.GENERIC, artifact.engineFamily);
    }

    @Test
    public void textExcerptsRespectTheConfiguredCap() {
        RenderedPageDocument document = highConfidenceDocument();
        LegacyBrowserSearchSettings tight = LayoutTestSupport.withDiagnostics(defaults,
                LayoutTestSupport.diagnosticsWithExcerptCap(10));
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(tight).analyze(document);

        SearchPageAnalysisArtifact artifact =
                new SearchPageAnalysisArtifactBuilder(tight).build(document, resolution, "q");

        assertFalse(artifact.containerCandidates.isEmpty());
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            assertTrue("excerpt must be capped: '" + candidate.textExcerpt + "'",
                    candidate.textExcerpt.length() <= 10);
            assertTrue("only known snapshot-local ids are exposed",
                    document.container(candidate.containerId) != null);
        }
    }

    @Test
    public void candidateCountNeverExceedsTheMechanicalCap() {
        RenderedPageDocument document = highConfidenceDocument();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);

        SearchPageAnalysisArtifact artifact =
                new SearchPageAnalysisArtifactBuilder(defaults).build(document, resolution, "q");

        assertTrue(artifact.containerCandidates.size()
                <= defaults.analysis.maximumCandidateContainers);
        assertEquals(resolution.scoredCandidates.size(), artifact.containerCandidates.size());
    }

    @Test
    public void lowConfidencePageOffersScoredCandidatesAsPreferredIds() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addPlainContainer("div", "", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 1280, 2000), 40, 0, 0, 0, 0);
        RenderedPageDocument document = serp.build();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);

        SearchPageAnalysisArtifact artifact =
                new SearchPageAnalysisArtifactBuilder(defaults).build(document, resolution, "q");

        assertEquals(MechanicalConfidenceOutcome.LOW_CONFIDENCE, artifact.mechanicalOutcome);
        // Preferred ids, when the mechanics have no organic container, are exactly the scored ids.
        assertEquals(artifact.containerCandidates.size(),
                artifact.mechanicallyPreferredContainerIds.size());
    }

    @Test
    public void digestIsStableForTheSameSettings() {
        RenderedPageDocument document = highConfidenceDocument();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);
        String a = new SearchPageAnalysisArtifactBuilder(defaults)
                .build(document, resolution, "q").settingsDigest;
        String b = new SearchPageAnalysisArtifactBuilder(defaults)
                .build(document, resolution, "q").settingsDigest;
        assertEquals(a, b);

        LegacyBrowserSearchSettings tight = LayoutTestSupport.withDiagnostics(defaults,
                LayoutTestSupport.diagnosticsWithExcerptCap(10));
        String c = new SearchPageAnalysisArtifactBuilder(tight)
                .build(document, resolution, "q").settingsDigest;
        assertFalse("changing a bound setting must change the digest", a.equals(c));
    }
}
