package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.render.RenderedPageFingerprint;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * A4e: a validated profile is reused only when engine family, fingerprint and structure/ancestry
 * signatures are compatible AND the current candidates re-resolve AND the re-derived selection
 * re-validates. It works from the ARTIFACT alone (the runtime holds no document) and re-derives the id
 * from the current artifact, never resurrecting a stored id. An incompatible fingerprint discards it.
 */
public class SearchPageLayoutProfileServiceTest {

    private final LegacyBrowserSearchSettings settings =
            LayoutTestSupport.forcingLowConfidence(LegacyBrowserSearchDefaults.create());
    private final SearchPageLayoutProfileService service =
            new SearchPageLayoutProfileService(settings.extraction);

    private SearchPageAnalysisArtifact artifactOf(RenderedPageDocument document) {
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(settings).analyze(document);
        return new SearchPageAnalysisArtifactBuilder(settings).build(document, resolution, "q");
    }

    private ValidatedSearchPageLayoutDecision decision(String snapshotId, String column) {
        return new ValidatedSearchPageLayoutDecision("analysis-x", snapshotId, 0L, "", "", column,
                Arrays.asList(column), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0.9);
    }

    @Test
    public void compatibleProfileIsReusedAndReDerivesTheCurrentContainerId() {
        SerpDocuments serp1 = SerpDocuments.builder();
        serp1.addNavigationBar(8);
        String col1 = serp1.addResultColumn(3, new RenderedBox(300, 120, 680, 560),
                SerpDocuments.WHITE);
        SearchPageAnalysisArtifact artifact1 = artifactOf(serp1.build());
        InMemorySearchPageLayoutProfileStore store = new InMemorySearchPageLayoutProfileStore();
        store.saveValidated(service.buildProfile(artifact1,
                decision(artifact1.snapshotId, col1), 1000L));

        // Same structure, two extra leading containers → the column's id differs.
        SerpDocuments serp2 = SerpDocuments.builder();
        serp2.addPlainContainer("div", "a", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 0, 100, 40), 40, 0, 0, 0, 0);
        serp2.addPlainContainer("div", "b", Collections.<String>emptyList(),
                Collections.<String>emptyList(), new RenderedBox(0, 40, 100, 40), 40, 0, 0, 0, 0);
        String col2 = serp2.addResultColumn(3, new RenderedBox(300, 120, 680, 560),
                SerpDocuments.WHITE);
        SearchPageAnalysisArtifact artifact2 = artifactOf(serp2.build());

        ValidatedSearchPageLayoutDecision reused =
                service.resolveFromProfiles(artifact2, store, 2000L);

        assertNotNull("a compatible profile must be reusable", reused);
        assertEquals("the id must be re-derived from the current artifact", col2,
                reused.primaryOrganicContainerId);
        assertEquals("using the profile re-saves it revalidated", 2, store.size());
    }

    @Test
    public void incompatibleFingerprintDiscardsTheProfile() {
        SerpDocuments serp1 = SerpDocuments.builder();
        serp1.addNavigationBar(8);
        String col1 = serp1.addResultColumn(3, new RenderedBox(300, 120, 680, 560),
                SerpDocuments.WHITE);
        SearchPageAnalysisArtifact artifact1 = artifactOf(serp1.build());
        InMemorySearchPageLayoutProfileStore store = new InMemorySearchPageLayoutProfileStore();
        store.saveValidated(service.buildProfile(artifact1,
                decision(artifact1.snapshotId, col1), 1000L));

        SerpDocuments serp2 = SerpDocuments.builder();
        serp2.addNavigationBar(8);
        serp2.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        SearchPageAnalysisArtifact artifact2 =
                artifactOf(withFingerprint(serp2.build(), "different-fingerprint"));

        assertNull("a different fingerprint must not reuse the profile",
                service.resolveFromProfiles(artifact2, store, 2000L));
    }

    private static RenderedPageDocument withFingerprint(RenderedPageDocument document, String value) {
        return new RenderedPageDocument(document.snapshotId, document.snapshotGeneration,
                document.pageUrl, document.pageTitle, document.viewport,
                new RenderedPageFingerprint(value), document.rootContainerIds, document.containers,
                document.links, document.captureTruncated, document.captureWarnings);
    }
}
