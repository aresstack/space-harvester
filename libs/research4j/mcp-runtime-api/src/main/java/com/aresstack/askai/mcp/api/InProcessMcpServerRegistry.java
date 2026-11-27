package com.aresstack.askai.mcp.api;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A transport-free reference implementation of {@link McpServerRegistry}: endpoints, tokens and tool sets live
 * in-JVM and tool calls are dispatched directly. This is NOT an MCP protocol implementation (there is no wire
 * format) — it lets the host and plugins register/gate tools and be unit-tested without a network. The Solon
 * streamable-HTTP transport (loopback, per-endpoint token over the wire, tools/list_changed) is a separate
 * adapter behind the same port; see problems.md MCP-P004.
 */
public final class InProcessMcpServerRegistry implements McpServerRegistry {

    /** Notified whenever an endpoint's tool set changes (the transport maps this to tools/list_changed). */
    public interface ToolsChangedListener {
        void onToolsChanged(String endpointId);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<String, Endpoint>();
    private final CopyOnWriteArrayList<ToolsChangedListener> listeners =
            new CopyOnWriteArrayList<ToolsChangedListener>();
    private volatile boolean shutdown;

    @Override
    public McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
        if (shutdown) {
            throw new IllegalStateException("registry is shut down");
        }
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        String token = newToken();
        endpoints.put(definition.getEndpointId(), new Endpoint(definition, token));
        return new McpEndpointHandle(definition.getEndpointId(), token);
    }

    @Override
    public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools) {
        Endpoint endpoint = authorized(handle);
        if (endpoint == null) {
            return;
        }
        Map<String, McpToolContribution> next = new LinkedHashMap<String, McpToolContribution>();
        if (tools != null) {
            for (McpToolContribution tool : tools) {
                next.put(tool.getName(), tool);
            }
        }
        endpoint.tools = next;
        fireToolsChanged(endpoint.definition.getEndpointId());
    }

    @Override
    public void unregisterEndpoint(McpEndpointHandle handle) {
        if (handle == null) {
            return;
        }
        Endpoint endpoint = endpoints.get(handle.getEndpointId());
        if (endpoint != null && endpoint.token.equals(handle.getToken())) {
            endpoints.remove(handle.getEndpointId());
        }
    }

    @Override
    public String endpointUrl(McpEndpointHandle handle) {
        Endpoint endpoint = authorized(handle);
        return endpoint == null ? null
                : "inprocess://" + endpoint.definition.getEndpointId() + "/" + endpoint.token;
    }

    public void addToolsChangedListener(ToolsChangedListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    /** @return the tool names currently offered by the endpoint, or empty on a bad token / unknown endpoint. */
    @Override
    public java.util.Map<String, String> toolCatalog(McpEndpointHandle handle) {
        java.util.Map<String, String> catalog = new LinkedHashMap<String, String>();
        if (handle != null) {
            Endpoint endpoint = endpoints.get(handle.getEndpointId());
            if (endpoint != null && endpoint.token.equals(handle.getToken())) {
                for (McpToolContribution tool : endpoint.tools.values()) {
                    catalog.put(tool.getName(), tool.getDescription());
                }
            }
        }
        return catalog;
    }

    @Override
    public List<String> toolNames(McpEndpointHandle handle) {
        return handle == null ? new ArrayList<String>()
                : listToolNames(handle.getEndpointId(), handle.getToken());
    }

    public List<String> listToolNames(String endpointId, String token) {
        Endpoint endpoint = lookup(endpointId, token);
        return endpoint == null ? new ArrayList<String>()
                : new ArrayList<String>(endpoint.tools.keySet());
    }

    /** Dispatch a tool call. A bad token, unknown endpoint or unknown tool yields an error result (no throw). */
    public McpToolResult invoke(String endpointId, String token, McpToolCall call) {
        Endpoint endpoint = lookup(endpointId, token);
        if (endpoint == null) {
            return McpToolResult.error("Unknown endpoint or invalid token.");
        }
        McpToolContribution tool = endpoint.tools.get(call.getToolName());
        if (tool == null) {
            return McpToolResult.error("Unknown tool: " + call.getToolName());
        }
        try {
            McpToolResult result = tool.getHandler().invoke(call);
            return result == null ? McpToolResult.error("Tool returned no result.") : result;
        } catch (RuntimeException ex) {
            return McpToolResult.error("Tool failed: " + ex.getMessage());
        }
    }

    /** Idempotent: clears all endpoints/tokens; subsequent registration throws. */
    public void shutdown() {
        shutdown = true;
        endpoints.clear();
        listeners.clear();
    }

    public boolean isEndpointRegistered(String endpointId) {
        return endpoints.containsKey(endpointId);
    }

    private Endpoint authorized(McpEndpointHandle handle) {
        return handle == null ? null : lookup(handle.getEndpointId(), handle.getToken());
    }

    private Endpoint lookup(String endpointId, String token) {
        if (shutdown || endpointId == null || token == null) {
            return null;
        }
        Endpoint endpoint = endpoints.get(endpointId);
        return endpoint != null && endpoint.token.equals(token) ? endpoint : null;
    }

    private void fireToolsChanged(String endpointId) {
        for (ToolsChangedListener listener : listeners) {
            listener.onToolsChanged(endpointId);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static final class Endpoint {
        private final McpEndpointDefinition definition;
        private final String token;
        private volatile Map<String, McpToolContribution> tools =
                new LinkedHashMap<String, McpToolContribution>();

        private Endpoint(McpEndpointDefinition definition, String token) {
            this.definition = definition;
            this.token = token;
        }
    }
}
