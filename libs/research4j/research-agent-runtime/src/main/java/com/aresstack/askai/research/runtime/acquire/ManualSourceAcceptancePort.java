package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.HashMap;
import java.util.Map;

/**
 * The USER-triggered search's source-acceptance route: the internal, phase-INDEPENDENT
 * {@code manual_source_accept} tool on the host's service endpoint (never an agent tool). It delegates to the
 * SAME host-side {@code SourceAcceptanceService} the agent's phase-gated {@code source_accept} uses — the only
 * difference is the endpoint/authorization, never the acquisition or acceptance logic. Return contract is
 * identical to {@link AgentSourceAcceptancePort} (the acceptance result line), so the shared
 * {@link com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService} treats both the same.
 */
public final class ManualSourceAcceptancePort implements SourceAcceptancePort {

    private final ToolInvoker service;
    private final String searchQuery;
    /** The manual-search request id — persisted with every source so reviews can scope to ONE search. */
    private final String searchRequestId;

    public ManualSourceAcceptancePort(ToolInvoker service, String searchQuery) {
        this(service, searchQuery, "");
    }

    public ManualSourceAcceptancePort(ToolInvoker service, String searchQuery, String searchRequestId) {
        this.service = service;
        this.searchQuery = searchQuery == null ? "" : searchQuery;
        this.searchRequestId = searchRequestId == null ? "" : searchRequestId;
    }

    @Override
    public String accept(String captureId) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return accept(captureId, false);
    }

    @Override
    public String accept(String captureId, boolean userRelevant)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return accept(captureId, userRelevant, "");
    }

    @Override
    public String accept(String captureId, boolean userRelevant, String languageCode)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("capture_id", captureId);
        if (!searchQuery.isEmpty()) {
            // Persist WHICH user query found this source, so "already searched" survives a restart.
            args.put("search_query", searchQuery);
        }
        if (userRelevant) {
            args.put("user_relevant", "true"); // the HUD ⭐ toggle for this page
        }
        if (!searchRequestId.isEmpty()) {
            args.put("search_request_id", searchRequestId);
        }
        if (languageCode != null && !languageCode.trim().isEmpty()) {
            // The language snapshot of the SEARCH that found this page - the knowledge job's world.
            args.put("language", languageCode.trim());
        }
        return service.call("manual_source_accept", args);
    }

    @Override
    public void park(String url, String title, String excerpt, double rerankScore)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        parkReporting(url, title, excerpt, rerankScore);
    }

    @Override
    public String parkReporting(String url, String title, String excerpt, double rerankScore)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("url", url);
        args.put("title", title == null ? "" : title);
        args.put("excerpt", excerpt == null ? "" : excerpt);
        if (!Double.isNaN(rerankScore)) {
            args.put("score", Double.toString(rerankScore));
        }
        if (!searchQuery.isEmpty()) {
            args.put("search_query", searchQuery);
        }
        if (!searchRequestId.isEmpty()) {
            args.put("search_request_id", searchRequestId);
        }
        return service.call("manual_source_park", args);
    }
}
