package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.McpLayoutRepairClient;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.LongSupplier;

/**
 * The legacy strategy: it delegates VERBATIM to the existing browser SERP path over MCP
 * ({@code web_search_prepare} / {@code web_search_apply_layout} via {@link McpLayoutRepairClient}). No SERP
 * parsing, layout repair, challenge handling or transit-host logic is reimplemented here — this class only
 * adapts the neutral {@link SearchStrategy} contract onto the unchanged repair client, so the browser-based
 * Bing/Google/DuckDuckGo discovery remains available exactly as before, now as one explicit strategy.
 */
public final class LegacyBrowserSearchStrategy implements EngineGroupSearchStrategy {

    private final McpLayoutRepairClient repairClient;
    private final LongSupplier nowEpochMillis;
    /** One priority group of Search → Engines, or null for the engines enabled in the browser's settings. */
    private final java.util.List<String> engineIds;

    public LegacyBrowserSearchStrategy(McpLayoutRepairClient repairClient, LongSupplier nowEpochMillis) {
        this(repairClient, nowEpochMillis, null);
    }

    private LegacyBrowserSearchStrategy(McpLayoutRepairClient repairClient, LongSupplier nowEpochMillis,
                                        java.util.List<String> engineIds) {
        if (repairClient == null) {
            throw new IllegalArgumentException("repairClient must not be null");
        }
        if (nowEpochMillis == null) {
            throw new IllegalArgumentException("nowEpochMillis must not be null");
        }
        this.repairClient = repairClient;
        this.nowEpochMillis = nowEpochMillis;
        this.engineIds = engineIds == null ? null
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(engineIds));
    }

    @Override
    public SearchStrategy forEngines(java.util.List<String> groupEngineIds) {
        return new LegacyBrowserSearchStrategy(repairClient, nowEpochMillis, groupEngineIds);
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, final CancellationSignal cancellation,
                                      final SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        // nowEpochMillis feeds only the layout-repair coordinator's clock-aware retries; it comes from the
        // loop's injected clock so scripted-clock tests stay faithful.
        long t0 = System.currentTimeMillis();
        McpLayoutRepairClient.Result result = repairClient.searchWithRepair(
                request.getQuery(), engineIds, cancellation, nowEpochMillis.getAsLong(),
                new McpLayoutRepairClient.ToolBudget() {
                    public boolean beforeToolCall() {
                        return budget.beforeToolCall();
                    }
                });
        // D1 diagnostics: the ORIGINAL repair outcome (before it collapses to RESULTS/TECHNICAL_PROBLEM/NO_RESULTS)
        // plus counts + the step diagnostics — so a first-click cold-start failure shows whether it was
        // EXTRACTION_FAILED, CHALLENGE_PENDING or something else, instead of an opaque SEARCH_TECHNICAL_PROBLEM.
        System.err.println("[initial-search] outcome=" + result.status
                + " candidates=" + result.candidates.size()
                + " providerHosts=" + result.providerHosts
                + " challenges=" + result.challenges.size()
                + " elapsedMs=" + (System.currentTimeMillis() - t0)
                + (result.diagnostics.isEmpty() ? "" : " diagnostics=" + result.diagnostics));
        return new InitialSearchResult(result.candidates, result.providerHosts, result.challenges,
                result.diagnostics, statusOf(result.status));
    }

    /**
     * Map the repair client's typed outcome onto the neutral search status. A layout that could not be
     * extracted ({@code EXTRACTION_FAILED} — e.g. the model-backed repair was unavailable) or an engine
     * blocked by a challenge with nothing extractable ({@code CHALLENGE_PENDING}) is a TECHNICAL_PROBLEM,
     * NOT an empty search: reporting those as "no relevant results" hides a technical failure the user can
     * retry. {@code NO_ORGANIC_RESULTS} is the only honest empty. Budget/cancel stops stay neutral — the
     * loop's own gates report the accurate budget/cancel reason.
     */
    private static InitialSearchStatus statusOf(McpLayoutRepairClient.Outcome outcome) {
        switch (outcome) {
            case ORGANIC_RESULTS:
                return InitialSearchStatus.RESULTS;
            case EXTRACTION_FAILED:
            case CHALLENGE_PENDING:
                return InitialSearchStatus.TECHNICAL_PROBLEM;
            case NO_ORGANIC_RESULTS:
            case BUDGET_EXHAUSTED:
            case CANCELLED:
            default:
                return InitialSearchStatus.NO_RESULTS;
        }
    }
}
