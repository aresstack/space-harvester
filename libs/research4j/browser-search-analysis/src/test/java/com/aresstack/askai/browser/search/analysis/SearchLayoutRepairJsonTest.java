package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The wire codec round-trips every binding value fully and hard-rejects malformed payloads. This is
 * the contract the MCP transport relies on — a lost or type-confused binding value would let a
 * decision reach the wrong snapshot.
 */
public class SearchLayoutRepairJsonTest {

    private final LegacyBrowserSearchSettings lowConf =
            LayoutTestSupport.forcingLowConfidence(LegacyBrowserSearchDefaults.create());

    private SearchLayoutRepairRequest aRepairRequest(String[] outCol) {
        SerpDocuments serp = SerpDocuments.builder();
        serp.addNavigationBar(8);
        String col = serp.addResultColumn(3, new RenderedBox(300, 120, 680, 560), SerpDocuments.WHITE);
        if (outCol != null) {
            outCol[0] = col;
        }
        RenderedPageDocument document = serp.build();
        return new WebSearchLayoutRepairService(lowConf, 4, 10_000L)
                .prepareSingle(document, "berlin", "engine.example", 1000L).repairRequests.get(0);
    }

    @Test
    public void preparedRepairRequestRoundTripsAllBindingValues() {
        SearchLayoutRepairRequest original = aRepairRequest(null);
        PreparedWebSearchResult prepared = new PreparedWebSearchResult(
                WebSearchPreparationStatus.REPAIR_REQUIRED, Collections.<com.aresstack.askai.browser
                .search.SearchResultCandidate>emptyList(), Arrays.asList(original),
                Arrays.asList("bing.example"),
                Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                Arrays.asList("prepared one repair"));

        PreparedWebSearchResult decoded = SearchLayoutRepairJson.decodePrepared(
                SearchLayoutRepairJson.encodePrepared(prepared));

        assertEquals(WebSearchPreparationStatus.REPAIR_REQUIRED, decoded.status);
        assertEquals(1, decoded.repairRequests.size());
        SearchLayoutRepairRequest r = decoded.repairRequests.get(0);
        assertEquals(original.attemptId.value, r.attemptId.value);
        assertEquals(original.snapshotId, r.snapshotId);
        assertEquals(original.snapshotGeneration, r.snapshotGeneration);
        assertEquals(original.documentFingerprint, r.documentFingerprint);
        assertEquals(original.layoutStructureFingerprint, r.layoutStructureFingerprint);
        assertEquals(original.artifact.analysisId, r.artifact.analysisId);
        assertEquals(original.artifact.settingsDigest, r.artifact.settingsDigest);
        assertEquals(original.artifact.containerCandidates.size(),
                r.artifact.containerCandidates.size());
        SearchPageContainerCandidate a = original.artifact.containerCandidates.get(0);
        SearchPageContainerCandidate b = r.artifact.containerCandidates.get(0);
        assertEquals(a.containerId, b.containerId);
        assertEquals(a.structureSignature, b.structureSignature);
        assertEquals(a.ancestrySignature, b.ancestrySignature);
        assertEquals(a.signalScores.size(), b.signalScores.size());
    }

    @Test
    public void submissionRoundTripsTheDecisionAndBinding() {
        SearchLayoutRepairRequest request = aRepairRequest(null);
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                request.artifact.analysisId, request.snapshotId, request.snapshotGeneration,
                request.documentFingerprint, request.artifact.settingsDigest, "container-0003",
                Arrays.asList("container-0003"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 0.88);
        SearchLayoutRepairSubmission original = new SearchLayoutRepairSubmission(request.attemptId,
                request.artifact.analysisId, request.snapshotId, request.snapshotGeneration,
                request.documentFingerprint, request.layoutStructureFingerprint,
                request.artifact.settingsDigest, decision);

        SearchLayoutRepairSubmission decoded = SearchLayoutRepairJson.decodeSubmission(
                SearchLayoutRepairJson.encodeSubmission(original));

        assertEquals(original.attemptId.value, decoded.attemptId.value);
        assertEquals(original.analysisId, decoded.analysisId);
        assertEquals(original.snapshotId, decoded.snapshotId);
        assertEquals(original.snapshotGeneration, decoded.snapshotGeneration);
        assertEquals(original.documentFingerprint, decoded.documentFingerprint);
        assertEquals(original.layoutStructureFingerprint, decoded.layoutStructureFingerprint);
        assertEquals(original.settingsDigest, decoded.settingsDigest);
        assertEquals("container-0003", decoded.decision.primaryOrganicContainerId);
        assertEquals(original.analysisId, decoded.decision.analysisId);
        assertEquals(request.snapshotGeneration, decoded.decision.snapshotGeneration);
        assertEquals(request.artifact.settingsDigest, decoded.decision.settingsDigest);
        assertEquals(0.88, decoded.decision.confidence, 1e-9);
    }

    @Test
    public void malformedPayloadsAreHardRejected() {
        // invalid JSON
        expectReject("not json");
        // missing required field
        expectReject("{\"snapshotId\":\"s\"}", true);
        // wrong type (repairTicketId as number)
        expectReject("{\"repairTicketId\":5,\"snapshotId\":\"s\",\"documentFingerprint\":\"f\","
                + "\"layoutStructureFingerprint\":\"l\",\"decision\":{}}", true);
    }

    @Test
    public void unknownEnumValueIsHardRejected() {
        String json = "{\"status\":\"TOTALLY_UNKNOWN\",\"candidates\":[],\"repairRequests\":[],"
                + "\"diagnostics\":[]}";
        try {
            SearchLayoutRepairJson.decodePrepared(json);
            fail("expected rejection of unknown status");
        } catch (SearchLayoutRepairJson.DecodeException expected) {
            // expected
        }
    }

    private void expectReject(String json) {
        expectReject(json, false);
    }

    private void expectReject(String json, boolean asSubmission) {
        try {
            if (asSubmission) {
                SearchLayoutRepairJson.decodeSubmission(json);
            } else {
                SearchLayoutRepairJson.decodePrepared(json);
            }
            fail("expected DecodeException for: " + json);
        } catch (SearchLayoutRepairJson.DecodeException expected) {
            // expected
        }
    }
}
