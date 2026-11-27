package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A4e: the typed diagnostic projection is bounded and honest. The candidate list respects the byte
 * budget, raw model responses appear ONLY when the setting is on, urls are redacted when configured,
 * and everything travels typed — no test parses the short ATTEMPT line as a data contract.
 */
public class SearchPageAnalysisDiagnosticArtifactBuilderTest {

    private SearchPageAnalysisArtifact artifactWithCandidates() {
        return LayoutTestSupport.artifactOf("snap-1-test",
                LayoutTestSupport.candidate("container-1", "container-root"),
                LayoutTestSupport.candidate("container-2", "container-root"),
                LayoutTestSupport.candidate("container-3", "container-root"));
    }

    private SearchPageLayoutResolverResult resultWithRaw(String raw) {
        SearchPageAnalysisAttempt attempt = new SearchPageAnalysisAttempt(1,
                StructuredInferenceStatus.SUCCESS, true, true, Collections.<String>emptyList(), raw);
        return new SearchPageLayoutResolverResult(SearchPageLayoutResolverOutcome.RESOLVED,
                "snap-1-test", null, null, Collections.singletonList(attempt), "diag");
    }

    @Test
    public void candidateListRespectsTheByteBudget() {
        SearchDiagnosticsSettings tight = LayoutTestSupport.diagnostics(false, 400, 600, false);
        SearchPageAnalysisDiagnosticArtifact diagnostic =
                new SearchPageAnalysisDiagnosticArtifactBuilder(tight)
                        .build(artifactWithCandidates(), null, "NONE", "EXTRACTION_FAILED");

        assertTrue("candidate count must be capped by the byte budget",
                diagnostic.mechanicalCandidates.size() < 3);
        assertTrue(diagnostic.truncated);
    }

    @Test
    public void rawResponsesOnlyAppearWhenTheSettingIsOn() {
        String raw = "{\"snapshotId\":\"snap-1-test\",\"organicResultContainerIds\":[]}";
        SearchDiagnosticsSettings off = LayoutTestSupport.diagnostics(false, 400, 262_144, false);
        SearchDiagnosticsSettings on = LayoutTestSupport.diagnostics(true, 400, 262_144, false);

        SearchPageAnalysisDiagnosticArtifact hidden =
                new SearchPageAnalysisDiagnosticArtifactBuilder(off)
                        .build(artifactWithCandidates(), resultWithRaw(raw), "NONE", "RESOLVED");
        assertFalse(hidden.rawModelResponsesIncluded);
        assertTrue(hidden.aiAttempts.get(0).rawResponse.isEmpty());

        SearchPageAnalysisDiagnosticArtifact shown =
                new SearchPageAnalysisDiagnosticArtifactBuilder(on)
                        .build(artifactWithCandidates(), resultWithRaw(raw), "NONE", "RESOLVED");
        assertTrue(shown.rawModelResponsesIncluded);
        assertTrue(shown.aiAttempts.get(0).rawResponse.contains("organicResultContainerIds"));
    }

    @Test
    public void urlsAreRedactedFromSurfacedRawWhenConfigured() {
        String raw = "note https://target.example.org/page here";
        SearchDiagnosticsSettings redacting = LayoutTestSupport.diagnostics(true, 400, 262_144, true);
        SearchPageAnalysisDiagnosticArtifact diagnostic =
                new SearchPageAnalysisDiagnosticArtifactBuilder(redacting)
                        .build(artifactWithCandidates(), resultWithRaw(raw), "NONE", "RESOLVED");

        String surfaced = diagnostic.aiAttempts.get(0).rawResponse;
        assertFalse(surfaced.contains("https://target.example.org"));
        assertTrue(surfaced.contains("[redacted-url]"));
    }

    @Test
    public void attemptsTravelTypedNotAsAParsedAttemptLine() {
        SearchDiagnosticsSettings on = LayoutTestSupport.diagnostics(true, 400, 262_144, false);
        SearchPageAnalysisDiagnosticArtifact diagnostic =
                new SearchPageAnalysisDiagnosticArtifactBuilder(on).build(artifactWithCandidates(),
                        resultWithRaw("{}"), "REJECTED", "RESOLVED");

        assertEquals(1, diagnostic.aiAttempts.size());
        assertEquals(StructuredInferenceStatus.SUCCESS,
                diagnostic.aiAttempts.get(0).inferenceStatus);
        assertEquals("REJECTED", diagnostic.profileOutcome);
        assertEquals("RESOLVED", diagnostic.finalOutcome);
    }
}
