package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * D2 diagnostics contract of the coordinator: when the AI resolver rejects (or exhausts) attempts,
 * the surfaced coordination diagnostics carry the CONCRETE per-attempt validation violations (kind +
 * message) — not just "VALIDATION_FAILED after N attempt(s)". Behaviour itself stays unchanged.
 */
public class SearchLayoutRepairCoordinatorTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    private SearchPageAnalysisArtifact artifact() {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(9);
        serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        RenderedPageDocument document = serp.build();
        SearchPageLayoutResolution resolution =
                new SearchPageMechanicalAnalyzer(defaults).analyze(document);
        return new SearchPageAnalysisArtifactBuilder(defaults).build(document, resolution, "q");
    }

    private SearchLayoutRepairRequest ticket(SearchPageAnalysisArtifact artifact) {
        return new SearchLayoutRepairRequest(new SearchLayoutRepairAttemptId("repair-x"), "q",
                "engine.example", artifact.engineFamily, artifact.snapshotId,
                artifact.snapshotGeneration, artifact.documentFingerprint, "layout-fp", artifact,
                new SearchPageAnalysisDiagnosticArtifactBuilder(defaults.diagnostics)
                        .build(artifact, null, "NONE", "REPAIR_REQUIRED"),
                1000L, 2000L);
    }

    @Test
    public void validationViolationsSurfaceInTheCoordinationDiagnostics() {
        SearchPageAnalysisArtifact artifact = artifact();
        String unknownIdResponse = "{\"analysisId\":\"" + artifact.analysisId
                + "\",\"snapshotId\":\"" + artifact.snapshotId + "\","
                + "\"organicResultContainerIds\":[\"container-9999\"],"
                + "\"resultBlockContainerIds\":[],"
                + "\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"made up\"}";
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(unknownIdResponse)
                .thenSuccess(unknownIdResponse)
                .thenSuccess(unknownIdResponse);
        SearchLayoutRepairCoordinator coordinator = new SearchLayoutRepairCoordinator(defaults,
                port, InferenceBudgetGate.ALLOW_ALL, RetryDelay.IMMEDIATE, null);

        SearchLayoutRepairCoordination coordination =
                coordinator.coordinate(ticket(artifact), CancellationSignal.NONE, 1000L);

        assertFalse("an invalid decision must never be submitted", coordination.shouldSubmit());
        String diagnostics = describe(coordination.diagnostics);
        assertTrue("summary line present: " + diagnostics,
                diagnostics.contains("AI layout resolver: VALIDATION_FAILED"));
        assertTrue("per-attempt line present: " + diagnostics,
                diagnostics.contains("AI attempt 1:"));
        assertTrue("the concrete violation kind must be surfaced: " + diagnostics,
                diagnostics.contains("UNKNOWN_CONTAINER_ID"));
    }

    private static String describe(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
