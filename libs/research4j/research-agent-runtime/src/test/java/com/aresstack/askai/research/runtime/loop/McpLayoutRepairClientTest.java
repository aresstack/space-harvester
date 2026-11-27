package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson;
import com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort;
import com.aresstack.askai.browser.search.analysis.WebSearchLayoutRepairService;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The runtime repair driver stays HONEST: a REPAIR_REQUIRED prepare whose only repair path is an
 * unavailable inference port ends in a typed EXTRACTION_FAILED (never fabricated organic results,
 * never an apply call without a validated decision), the per-ticket diagnostics name the mechanical
 * situation, and cancellation stops the ticket loop promptly.
 */
public class McpLayoutRepairClientTest {

    private static final McpLayoutRepairClient.ToolBudget UNLIMITED =
            new McpLayoutRepairClient.ToolBudget() {
                public boolean beforeToolCall() {
                    return true;
                }
            };

    /** A real low-confidence prepare over the shared synthetic SERP: exactly one repair ticket. */
    private PreparedWebSearchResult repairRequired() {
        WebSearchLayoutRepairService service = new WebSearchLayoutRepairService(
                RepairBridgeFixtures.lowConfidenceAiEnabled(), 3, 60_000L);
        return service.prepareSingle(RepairBridgeFixtures.navPlusColumn(), "q", "engine.example",
                1000L);
    }

    /** The same ticket without a harvested fallback — a page with no external link at all. */
    private PreparedWebSearchResult repairRequiredWithoutFallback() {
        PreparedWebSearchResult prepared = repairRequired();
        return new PreparedWebSearchResult(prepared.status,
                new ArrayList<com.aresstack.askai.browser.search.SearchResultCandidate>(),
                prepared.repairRequests, prepared.providerHosts, prepared.engineAttempts,
                prepared.challenges, prepared.diagnostics);
    }

    private McpLayoutRepairClient unavailableRepairClient(FakeBrowser browser) {
        return new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(RepairBridgeFixtures.lowConfidenceAiEnabled(),
                        new UnavailableStructuredInferencePort(), InferenceBudgetGate.ALLOW_ALL,
                        RetryDelay.IMMEDIATE, null));
    }

    @Test
    public void everyRepairFailingFallsBackToTheHarvestedLinks() throws Exception {
        // Live on Bing: three repair attempts failed and the search ended "technically failed"
        // although the page listed results. The harvested external links are the answer then.
        PreparedWebSearchResult prepared = repairRequired();
        assertFalse("fixture: the prepare carried a harvested fallback",
                prepared.candidates.isEmpty());
        FakeBrowser browser = new FakeBrowser();
        browser.answers.put("web_search_prepare", SearchLayoutRepairJson.encodePrepared(prepared));

        McpLayoutRepairClient.Result result = unavailableRepairClient(browser)
                .searchWithRepair("q", CancellationSignal.NONE, 1000L, UNLIMITED);

        assertEquals(McpLayoutRepairClient.Outcome.ORGANIC_RESULTS, result.status);
        assertEquals(prepared.candidates.size(), result.candidates.size());
        assertFalse("apply must never run without a validated decision",
                browser.calls.contains("web_search_apply_layout"));
        String diagnostics = describe(result.diagnostics);
        assertTrue("the AI outcome stays visible: " + diagnostics,
                diagnostics.contains("AI_UNAVAILABLE"));
        assertTrue("the fallback is named: " + diagnostics,
                diagnostics.contains("falling back to"));
    }

    @Test
    public void aLayoutTheModelFailedOnIsNotRetriedOnTheSameEnginesNextPages() throws Exception {
        // Live: three Bing pages, three tickets, 3 x 3 model calls all naming the same forbidden
        // container. After the first page failed validation the other pages are skipped.
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                RepairBridgeFixtures.lowConfidenceAiEnabledWithoutHarvest();
        PreparedWebSearchResult page1 = new WebSearchLayoutRepairService(settings, 3, 60_000L)
                .prepareSingle(RepairBridgeFixtures.navPlusColumn(), "q", "engine.example", 1000L);
        PreparedWebSearchResult page2 = new WebSearchLayoutRepairService(settings, 3, 60_000L)
                .prepareSingle(RepairBridgeFixtures.navPlusColumn(), "q", "engine.example", 1000L);
        PreparedWebSearchResult prepared = WebSearchLayoutRepairService.merge(
                java.util.Arrays.asList(page1, page2));
        assertEquals(2, prepared.repairRequests.size());
        FakeBrowser browser = new FakeBrowser();
        browser.answers.put("web_search_prepare", SearchLayoutRepairJson.encodePrepared(prepared));
        final java.util.concurrent.atomic.AtomicInteger modelCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.aresstack.askai.browser.search.inference.StructuredInferencePort rootPicker =
                new com.aresstack.askai.browser.search.inference.StructuredInferencePort() {
                    public com.aresstack.askai.browser.search.inference.StructuredInferenceResult
                            execute(com.aresstack.askai.browser.search.inference
                                    .StructuredInferenceRequest request) {
                        modelCalls.incrementAndGet();
                        return com.aresstack.askai.browser.search.inference.StructuredInferenceResult
                                .success("{\"analysisId\":\"analysis-snap-1-itest-1\","
                                        + "\"snapshotId\":\"snap-1-itest\","
                                        + "\"organicResultContainerIds\":[\"container-0001\"],"
                                        + "\"resultBlockContainerIds\":[],"
                                        + "\"excludedContainerIds\":[],"
                                        + "\"confidence\":0.9,\"explanation\":\"whole page\"}");
                    }
                };
        McpLayoutRepairClient client = new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(settings, rootPicker, InferenceBudgetGate.ALLOW_ALL,
                        RetryDelay.IMMEDIATE, null));

        McpLayoutRepairClient.Result result =
                client.searchWithRepair("q", CancellationSignal.NONE, 1000L, UNLIMITED);

        assertEquals(McpLayoutRepairClient.Outcome.EXTRACTION_FAILED, result.status);
        assertEquals("only the first page's attempts reach the model",
                settings.aiLayoutResolver.retryPolicy.maximumAttempts, modelCalls.get());
        String diagnostics = describe(result.diagnostics);
        assertTrue("the skip is named: " + diagnostics,
                diagnostics.contains("already failed validation on an earlier page"));
    }

    @Test
    public void aiUnavailableRepairEndsInHonestExtractionFailedWithPerTicketDiagnostics()
            throws Exception {
        PreparedWebSearchResult prepared = repairRequiredWithoutFallback();
        assertEquals(WebSearchPreparationStatus.REPAIR_REQUIRED, prepared.status);
        FakeBrowser browser = new FakeBrowser();
        browser.answers.put("web_search_prepare", SearchLayoutRepairJson.encodePrepared(prepared));
        McpLayoutRepairClient client = new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(RepairBridgeFixtures.lowConfidenceAiEnabled(),
                        new UnavailableStructuredInferencePort(), InferenceBudgetGate.ALLOW_ALL,
                        RetryDelay.IMMEDIATE, null));

        McpLayoutRepairClient.Result result =
                client.searchWithRepair("q", CancellationSignal.NONE, 1000L, UNLIMITED);

        assertEquals(McpLayoutRepairClient.Outcome.EXTRACTION_FAILED, result.status);
        assertTrue(result.candidates.isEmpty());
        assertFalse("apply must never run without a validated decision",
                browser.calls.contains("web_search_apply_layout"));
        String diagnostics = describe(result.diagnostics);
        assertTrue("the honest AI outcome must be visible: " + diagnostics,
                diagnostics.contains("AI_UNAVAILABLE"));
        assertTrue("per-ticket summary names the engine: " + diagnostics,
                diagnostics.contains("repair ticket engine=engine.example"));
        assertTrue("per-ticket summary names the mechanical confidence: " + diagnostics,
                diagnostics.contains("mechanicalConfidence="));
        assertTrue("per-ticket summary names the capture stability: " + diagnostics,
                diagnostics.contains("capture=stable"));
    }

    @Test
    public void cancellationStopsTheTicketLoopPromptly() throws Exception {
        PreparedWebSearchResult prepared = repairRequired();
        FakeBrowser browser = new FakeBrowser();
        browser.answers.put("web_search_prepare", SearchLayoutRepairJson.encodePrepared(prepared));
        McpLayoutRepairClient client = new McpLayoutRepairClient(browser,
                new SearchLayoutRepairCoordinator(RepairBridgeFixtures.lowConfidenceAiEnabled(),
                        new UnavailableStructuredInferencePort(), InferenceBudgetGate.ALLOW_ALL,
                        RetryDelay.IMMEDIATE, null));

        McpLayoutRepairClient.Result result = client.searchWithRepair("q",
                new CancellationSignal() {
                    public boolean isCancelled() {
                        return true;
                    }
                }, 1000L, UNLIMITED);

        assertEquals(McpLayoutRepairClient.Outcome.CANCELLED, result.status);
        assertFalse(browser.calls.contains("web_search_apply_layout"));
    }

    private static String describe(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void aPriorityGroupNamesItsEnginesInThePrepareCall() throws Exception {
        PreparedWebSearchResult prepared = repairRequired();
        FakeBrowser browser = new FakeBrowser();
        browser.answers.put("web_search_prepare", SearchLayoutRepairJson.encodePrepared(prepared));
        unavailableRepairClient(browser).searchWithRepair("q", java.util.Arrays.asList("duckduckgo", "bing"),
                CancellationSignal.NONE, 1000L, UNLIMITED);
        assertEquals("duckduckgo,bing", browser.args.get(0).get("engines"));
        unavailableRepairClient(browser).searchWithRepair("q", CancellationSignal.NONE, 1000L, UNLIMITED);
        assertFalse("without a group the settings' engines are searched",
                browser.args.get(browser.args.size() - 1).containsKey("engines"));
    }

    /** Serves canned tool answers and records every call. */
    private static final class FakeBrowser implements ToolInvoker {
        final List<String> calls = new ArrayList<String>();
        final List<Map<String, Object>> args = new ArrayList<Map<String, Object>>();
        final Map<String, String> answers = new HashMap<String, String>();

        public String call(String toolName, Map<String, Object> args) throws ToolFailure {
            calls.add(toolName);
            if ("web_search_prepare".equals(toolName)) {
                this.args.add(args);
            }
            String answer = answers.get(toolName);
            if (answer == null) {
                throw new ToolFailure("unexpected tool call: " + toolName);
            }
            return answer;
        }
    }
}
