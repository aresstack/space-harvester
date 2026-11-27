package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The transport-agnostic handler logic behind the three MCP repair tools — String in, String out, so
 * the SAME code serves the productive Java-21 sidecar registration and the cross-process integration
 * test. It stays MODEL-FREE: it captures/extracts mechanically and applies a runtime-validated
 * decision to a cached snapshot; it never calls a model. The clock is injected so the sidecar owns
 * time and tests stay deterministic.
 */
public final class SearchLayoutRepairTools {

    /** Diagnostics line naming how many SERP pages each engine host delivered ("... bing=2"). */
    public static final String SERP_PAGES_DIAGNOSTIC_PREFIX = "serp-pages:";

    private final WebSearchLayoutRepairService service;
    private final RenderedPageSource pageSource;
    private final LongSupplier clock;
    /** How the enabled engines are worked through — the USER's decision, carried here unchanged. */
    private final com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode;

    public SearchLayoutRepairTools(LegacyBrowserSearchSettings settings, RenderedPageSource pageSource,
                                   LongSupplier clock) {
        this.service = new WebSearchLayoutRepairService(settings,
                settings.layoutRepair.maximumCachedTickets, settings.layoutRepair.ticketTtlMillis);
        this.pageSource = pageSource;
        this.clock = clock;
        this.mode = settings.navigation.engineSelection.getMode();
    }

    /** {@code web_search_prepare(query)} → encoded {@link PreparedWebSearchResult}. */
    public String prepare(final String query) {
        return prepare(query, null);
    }

    /**
     * {@code web_search_prepare(query, engines)}: the engines' pages are merged with {@code modeOverride} (one priority
     * group: all of them), or with the settings' mode when it is null.
     */
    public String prepare(final String query,
                          final com.aresstack.askai.browser.search.engine.EngineAcquisitionMode modeOverride) {
        final com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode =
                modeOverride != null ? modeOverride : this.mode;
        final long now = clock.getAsLong();
        // Each page is analysed AS IT IS CAPTURED, so the navigation learns immediately whether this
        // engine delivered. Analysing afterwards meant every engine was always visited, whatever the
        // configuration said.
        final List<PreparedWebSearchResult> perEngine = new ArrayList<PreparedWebSearchResult>();
        // How many SERP pages each engine REALLY delivered into this search — the observable truth
        // the user asked for ("blättert er überhaupt?"), carried as a typed diagnostics line.
        final java.util.LinkedHashMap<String, Integer> serpPagesPerHost =
                new java.util.LinkedHashMap<String, Integer>();
        RenderedPageSource.EngineCapture capture = pageSource.capture(query,
                new RenderedPageSource.PageEvaluator() {
                    public RenderedPageSource.PageVerdict judge(
                            com.aresstack.askai.browser.render.RenderedPageDocument document,
                            String engineHost) {
                        Integer pagesSoFar = serpPagesPerHost.get(engineHost);
                        serpPagesPerHost.put(engineHost, pagesSoFar == null ? 1 : pagesSoFar + 1);
                        PreparedWebSearchResult single =
                                service.prepareSingle(document, query, engineHost, now);
                        perEngine.add(single);
                        // A page that still needs a layout repair has NOT delivered yet (the repair
                        // runs later, in the runtime) — but it is not EMPTY either: the engine's
                        // deeper pages stay worth fetching, and the next engine worth visiting.
                        switch (single.status) {
                            case ORGANIC_RESULTS:
                                return RenderedPageSource.PageVerdict.DELIVERED;
                            case NO_ORGANIC_RESULTS:
                                return RenderedPageSource.PageVerdict.EMPTY;
                            default:
                                return RenderedPageSource.PageVerdict.UNUSABLE;
                        }
                    }
                });
        PreparedWebSearchResult merged = WebSearchLayoutRepairService.merge(perEngine, mode);
        List<String> diagnostics = new ArrayList<String>(merged.diagnostics);
        if (!serpPagesPerHost.isEmpty()) {
            StringBuilder serp = new StringBuilder(SERP_PAGES_DIAGNOSTIC_PREFIX);
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> entry : serpPagesPerHost.entrySet()) {
                serp.append(first ? " " : ", ").append(entry.getKey()).append('=')
                        .append(entry.getValue());
                first = false;
            }
            diagnostics.add(serp.toString());
        }
        // Carry the navigation metadata (provider hosts, per-engine attempts, challenges) so the
        // research loop keeps the full legacy web_search behaviour.
        PreparedWebSearchResult withMetadata = new PreparedWebSearchResult(merged.status,
                merged.candidates, merged.repairRequests, capture.providerHosts,
                capture.engineAttempts, capture.challenges, diagnostics);
        return SearchLayoutRepairJson.encodePrepared(withMetadata);
    }

    /** {@code web_search_apply_layout(submission)} → encoded {@link SearchLayoutRepairResult}. */
    public String applyLayout(String submissionJson) {
        SearchLayoutRepairSubmission submission;
        try {
            submission = SearchLayoutRepairJson.decodeSubmission(submissionJson);
        } catch (SearchLayoutRepairJson.DecodeException bad) {
            return SearchLayoutRepairJson.encodeRepairResult(new SearchLayoutRepairResult(
                    SearchLayoutRepairStatus.INVALID_DECISION,
                    Collections.<com.aresstack.askai.browser.search.SearchResultCandidate>emptyList(),
                    Collections.singletonList("undecodable submission: " + bad.getMessage())));
        }
        return SearchLayoutRepairJson.encodeRepairResult(service.apply(submission, clock.getAsLong()));
    }

    /** {@code web_search_discard_repair(repairTicketId)} → a short status line. */
    public String discard(String repairTicketId) {
        service.discard(new com.aresstack.askai.browser.search.repair
                .SearchLayoutRepairAttemptId(repairTicketId));
        return "DISCARDED " + repairTicketId;
    }

    /** Cleanup for session close / browser recovery / shutdown. */
    public void clear() {
        service.clear();
    }
}
