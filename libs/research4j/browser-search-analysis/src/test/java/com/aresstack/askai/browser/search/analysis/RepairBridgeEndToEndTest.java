package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The real two-step process contract, end to end across both halves via the neutral DTOs: the
 * model-free sidecar service emits a bounded repair request; the model-using runtime coordinator
 * resolves it (profile first, then StructuredInferencePort, validating exclusively against
 * snapshot-local descriptors); the sidecar re-checks every guard and applies the validated decision
 * through the existing A3 extraction, yielding real candidates. A profile hit calls no model; AI
 * unavailable gives up so the next engine is tried.
 */
public class RepairBridgeEndToEndTest {

    private final LegacyBrowserSearchSettings settings = LayoutTestSupport.withAiLayoutResolver(
            LayoutTestSupport.forcingLowConfidence(LegacyBrowserSearchDefaults.create()),
            LayoutTestSupport.aiSettings(true, "profile-x", LayoutTestSupport.retryPolicy(3)));

    private RenderedPageDocument columnDocument(String[] outCol) {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        String col = serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        if (outCol != null) {
            outCol[0] = col;
        }
        return serp.build();
    }

    private String response(String snapshotId, String organicId) {
        return "{\"analysisId\":\"analysis-" + snapshotId + "-1\",\"snapshotId\":\"" + snapshotId + "\","
                + "\"organicResultContainerIds\":[\"" + organicId + "\"],"
                + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"repeated blocks\"}";
    }

    private SearchLayoutRepairCoordinator coordinator(ScriptedStructuredInferencePort port,
                                                      SearchPageLayoutProfileStore store) {
        return new SearchLayoutRepairCoordinator(settings, port, InferenceBudgetGate.ALLOW_ALL,
                RetryDelay.IMMEDIATE, store);
    }

    @Test
    public void lowConfidencePageIsRepairedThroughTheModelAndYieldsRealCandidates() {
        WebSearchLayoutRepairService sidecar = new WebSearchLayoutRepairService(settings, 4, 10_000L);
        String[] col = new String[1];
        RenderedPageDocument document = columnDocument(col);

        PreparedWebSearchResult prepared = sidecar.prepareSingle(document, "berlin", "engine", 1000L);
        assertEquals(WebSearchPreparationStatus.REPAIR_REQUIRED, prepared.status);
        SearchLayoutRepairRequest request = prepared.repairRequests.get(0);

        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response(request.snapshotId, col[0]));
        SearchLayoutRepairCoordination coordination =
                coordinator(port, null).coordinate(request, CancellationSignal.NONE, 2000L);

        assertEquals(SearchLayoutRepairCoordination.Outcome.SUBMIT, coordination.outcome);
        assertEquals(1, port.callCount());

        SearchLayoutRepairResult applied = sidecar.apply(coordination.submission, 3000L);
        assertEquals(SearchLayoutRepairStatus.ORGANIC_RESULTS, applied.status);
        assertEquals(3, applied.candidates.size());
        assertEquals("Result 0 title", applied.candidates.get(0).title);
        assertTrue(applied.candidates.get(0).snippet.contains("Snippet for result 0"));
    }

    @Test
    public void aLearnedProfileResolvesTheNextPageWithoutAnyModelCall() {
        InMemorySearchPageLayoutProfileStore store = new InMemorySearchPageLayoutProfileStore();

        // First page: AI resolves and a structural profile is learned.
        WebSearchLayoutRepairService sidecar1 = new WebSearchLayoutRepairService(settings, 4, 10_000L);
        String[] col1 = new String[1];
        SearchLayoutRepairRequest request1 =
                sidecar1.prepareSingle(columnDocument(col1), "q", "engine", 1000L)
                        .repairRequests.get(0);
        ScriptedStructuredInferencePort port1 = new ScriptedStructuredInferencePort()
                .thenSuccess(response(request1.snapshotId, col1[0]));
        SearchLayoutRepairCoordination first =
                coordinator(port1, store).coordinate(request1, CancellationSignal.NONE, 2000L);
        assertFalse(first.profileHit);
        assertEquals(1, port1.callCount());

        // Second, structurally identical page: the profile serves it with zero model calls.
        WebSearchLayoutRepairService sidecar2 = new WebSearchLayoutRepairService(settings, 4, 10_000L);
        String[] col2 = new String[1];
        RenderedPageDocument document2 = columnDocument(col2);
        SearchLayoutRepairRequest request2 =
                sidecar2.prepareSingle(document2, "q", "engine", 3000L).repairRequests.get(0);
        ScriptedStructuredInferencePort port2 = new ScriptedStructuredInferencePort();
        SearchLayoutRepairCoordination second =
                coordinator(port2, store).coordinate(request2, CancellationSignal.NONE, 4000L);

        assertEquals(SearchLayoutRepairCoordination.Outcome.SUBMIT, second.outcome);
        assertTrue("second page must be served by the profile", second.profileHit);
        assertEquals("a profile hit must not call the model", 0, port2.callCount());

        SearchLayoutRepairResult applied = sidecar2.apply(second.submission, 5000L);
        assertEquals(SearchLayoutRepairStatus.ORGANIC_RESULTS, applied.status);
        assertEquals(3, applied.candidates.size());
    }

    @Test
    public void unknownIdFromTheModelIsRepairedThenSubmitted() {
        WebSearchLayoutRepairService sidecar = new WebSearchLayoutRepairService(settings, 4, 10_000L);
        String[] col = new String[1];
        SearchLayoutRepairRequest request =
                sidecar.prepareSingle(columnDocument(col), "q", "engine", 1000L).repairRequests.get(0);
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response(request.snapshotId, "container-9999"))
                .thenSuccess(response(request.snapshotId, col[0]));

        SearchLayoutRepairCoordination coordination =
                coordinator(port, null).coordinate(request, CancellationSignal.NONE, 2000L);

        assertEquals(SearchLayoutRepairCoordination.Outcome.SUBMIT, coordination.outcome);
        assertEquals("must have repaired the unknown id", 2, port.callCount());
        assertEquals(col[0], coordination.submission.decision.primaryOrganicContainerId);
    }

    @Test
    public void aiUnavailableGivesUpSoTheNextEngineCanBeTried() {
        WebSearchLayoutRepairService sidecar = new WebSearchLayoutRepairService(settings, 4, 10_000L);
        SearchLayoutRepairRequest request =
                sidecar.prepareSingle(columnDocument(null), "q", "engine", 1000L)
                        .repairRequests.get(0);
        SearchLayoutRepairCoordinator coordinator = new SearchLayoutRepairCoordinator(settings,
                new UnavailableStructuredInferencePort(), InferenceBudgetGate.ALLOW_ALL,
                RetryDelay.IMMEDIATE, null);

        SearchLayoutRepairCoordination coordination =
                coordinator.coordinate(request, CancellationSignal.NONE, 2000L);
        assertEquals(SearchLayoutRepairCoordination.Outcome.GIVE_UP, coordination.outcome);
    }
}
