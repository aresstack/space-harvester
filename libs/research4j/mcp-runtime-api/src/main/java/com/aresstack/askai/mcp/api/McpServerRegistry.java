package com.aresstack.askai.mcp.api;

import java.util.Collection;

/**
 * The generic MCP server registry port. Endpoints are registered with a random token, their tool sets can be
 * updated dynamically (emitting a tool-list-changed signal), and they are unregistered (invalidating the
 * token). This port has NO Solon/MCP-SDK type; a Solon streamable-HTTP transport implements it behind the
 * scenes (loopback-only, per-endpoint token), and an in-process reference registry implements it for wiring
 * and tests.
 */
public interface McpServerRegistry {

    McpEndpointHandle registerEndpoint(McpEndpointDefinition definition);

    void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools);

    void unregisterEndpoint(McpEndpointHandle handle);

    /**
     * The client-facing URL of a registered endpoint (its token embedded in the path), or {@code null} when
     * the handle is unknown/unregistered. The in-process registry returns a non-network {@code inprocess:}
     * URI usable only for identity, never for connecting.
     */
    String endpointUrl(McpEndpointHandle handle);

    /**
     * The CURRENT tool names of a registered endpoint (live, on demand — never a cached client copy), or
     * an empty list when the handle is unknown/unregistered.
     */
    java.util.List<String> toolNames(McpEndpointHandle handle);

    /**
     * The CURRENT tools as name -> description (live, insertion-ordered) — the self-describing catalog a
     * UI can show without hardcoding anything. Empty when the handle is unknown/unregistered.
     */
    java.util.Map<String, String> toolCatalog(McpEndpointHandle handle);
}
