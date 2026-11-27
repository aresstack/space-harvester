package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.HashMap;
import java.util.Map;

/**
 * The autonomous agent's source-acceptance route: the phase-gated {@code source_accept} MCP tool on the
 * research endpoint. This is exactly what {@code ResearchLoop} already did inline
 * ({@code research.call("source_accept", {capture_id})}); extracting it behind {@link SourceAcceptancePort}
 * changes no behavior and lets a user-triggered search later use a different port (the internal
 * {@code manual_source_accept}) without touching the acquisition code.
 */
public final class AgentSourceAcceptancePort implements SourceAcceptancePort {

    private final ToolInvoker research;

    public AgentSourceAcceptancePort(ToolInvoker research) {
        this.research = research;
    }

    @Override
    public String accept(String captureId) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("capture_id", captureId);
        return research.call("source_accept", args);
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
        return research.call("source_park", args);
    }
}
