package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A4c at the resolver level: an unknown id triggers a repair retry and is NEVER applied; a stale
 * snapshot is hard-rejected; wrong field types fail typed; exhaustion fails typed with a complete,
 * flat-anchor-free attempt history.
 */
public class AiLayoutRepairTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    private SearchPageAnalysisArtifact artifact() {
        return LayoutTestSupport.artifactOf("snap-1-test",
                LayoutTestSupport.candidate("container-root", ""),
                LayoutTestSupport.candidate("container-col", "container-root"),
                LayoutTestSupport.candidate("container-b1", "container-col"),
                LayoutTestSupport.candidate("container-b2", "container-col"));
    }

    private SearchPageLayoutResolutionRequest request(SearchPageAnalysisArtifact artifact,
                                                      int maxAttempts) {
        return new SearchPageLayoutResolutionRequest(artifact,
                LayoutTestSupport.aiSettings(true, "profile-x",
                        LayoutTestSupport.retryPolicy(maxAttempts)),
                defaults.diagnostics, CancellationSignal.NONE);
    }

    private String response(String snapshotId, String organicId) {
        return "{\"analysisId\":\"analysis-" + snapshotId + "-1\",\"snapshotId\":\"" + snapshotId + "\","
                + "\"organicResultContainerIds\":[\"" + organicId + "\"],"
                + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"x\"}";
    }

    private AiSearchPageLayoutResolver resolver(ScriptedStructuredInferencePort port) {
        return new AiSearchPageLayoutResolver(port, defaults.extraction);
    }

    @Test
    public void unknownIdIsRepairedAndNeverApplied() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response("snap-1-test", "container-9999"))
                .thenSuccess(response("snap-1-test", "container-col"));
        SearchPageLayoutResolverResult result = resolver(port).resolve(request(artifact, 3));

        assertEquals(SearchPageLayoutResolverOutcome.RESOLVED, result.outcome);
        assertEquals(2, port.callCount());
        assertNotNull(result.validatedDecision);
        assertEquals("the unknown id must never reach the validated decision",
                "container-col", result.validatedDecision.primaryOrganicContainerId);
        assertFalse("the unknown-id attempt was not accepted", result.attempts.get(0).accepted);
        assertTrue(result.attempts.get(0).violations.toString().contains("UNKNOWN_CONTAINER_ID"));
    }

    @Test
    public void persistentUnknownIdExhaustsAndFailsTyped() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response("snap-1-test", "container-9999"))
                .thenSuccess(response("snap-1-test", "container-8888"));
        SearchPageLayoutResolverResult result = resolver(port).resolve(request(artifact, 2));

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertNull(result.validatedDecision);
        assertEquals(2, result.attempts.size());
        for (int i = 0; i < result.attempts.size(); i++) {
            assertFalse(result.attempts.get(i).accepted);
        }
    }

    @Test
    public void staleSnapshotDecisionIsHardRejected() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess(response("snap-OTHER", "container-col"));
        SearchPageLayoutResolverResult result = resolver(port).resolve(request(artifact, 1));

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertNull(result.validatedDecision);
        assertTrue(result.attempts.get(0).violations.toString().contains("UNKNOWN_SNAPSHOT"));
    }

    @Test
    public void wrongFieldTypeFailsTyped() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort().thenSuccess(
                "{\"snapshotId\":\"snap-1-test\",\"organicResultContainerIds\":[\"container-col\"],"
                        + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                        + "\"confidence\":\"high\"}");
        SearchPageLayoutResolverResult result = resolver(port).resolve(request(artifact, 1));

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertFalse(result.attempts.get(0).parsed);
    }
}
