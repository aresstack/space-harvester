package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link ToolInvoker}: dispatches IN-PROCESS to the host's current tool contributions of one
 * endpoint (research control, the internal service lane or the browser bridge) — no HTTP, no MCP client,
 * no token. The host endpoint hands its tool list over on every (re)publication; the same contributions
 * stay registered on the host's MCP server for external clients. Error mapping as the former MCP client:
 * an error result or a handler exception is a {@link ToolFailure} carrying the tool's own text; an endpoint
 * that was closed with its session is {@link EndpointUnavailable}.
 */
public final class ContributionToolInvoker implements ToolInvoker {

    private volatile Map<String, McpToolContribution> tools = Collections.emptyMap();
    private volatile boolean closed;
    /** Every tool name an earlier publication offered: one missing now left the offer with a state change. */
    private final java.util.Set<String> offeredBefore =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** The prefix every caller already maps to a state change (see {@code ResearchStopReason.STATE_CHANGED}). */
    public static final String STATE_CHANGED_PREFIX = "Not allowed in the current state (";

    public ContributionToolInvoker() {
    }

    public ContributionToolInvoker(Collection<McpToolContribution> tools) {
        setTools(tools);
    }

    /** Replace the published tool set (the endpoint calls this on open and on every tools/list_changed). */
    public void setTools(Collection<McpToolContribution> contributions) {
        Map<String, McpToolContribution> byName = new LinkedHashMap<String, McpToolContribution>();
        if (contributions != null) {
            for (McpToolContribution tool : contributions) {
                byName.put(tool.getName(), tool);
            }
        }
        offeredBefore.addAll(this.tools.keySet());
        this.tools = Collections.unmodifiableMap(byName);
    }

    /** The endpoint is gone: every further call reports it as unavailable. */
    public void close() {
        closed = true;
    }

    @Override
    public List<String> toolNames() {
        return new ArrayList<String>(tools.keySet());
    }

    @Override
    public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
        if (closed) {
            throw new EndpointUnavailable("endpoint closed");
        }
        McpToolContribution tool = tools.get(toolName);
        if (tool == null) {
            if (offeredBefore.contains(toolName)) {
                // plan PR 15: the tool was offered before and a phase change withdrew it — the same
                // state-changed result a write handler gives when its server-side re-check fails
                throw new ToolFailure(STATE_CHANGED_PREFIX + "the phase changed and " + toolName
                        + " is no longer offered).");
            }
            throw new ToolFailure("Unknown tool: " + toolName);
        }
        McpToolResult result;
        try {
            result = tool.getHandler().invoke(new McpToolCall(toolName, args));
        } catch (RuntimeException ex) {
            throw new ToolFailure("Tool failed: " + ex.getMessage());
        }
        if (result == null) {
            throw new ToolFailure("Tool returned no result.");
        }
        String text = result.getText();
        if (result.isError() || text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
            throw new ToolFailure(text);
        }
        return text;
    }
}
