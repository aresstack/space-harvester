package com.aresstack.askai.mcp.api;

/**
 * Host-provided factory for {@link McpToolClient}s. The URL carries the endpoint token in its path (like all
 * session endpoints in this runtime); implementations must not log it.
 */
public interface McpToolClientFactory {

    /** A client with the implementation's default per-call timeout (quick control-style calls). */
    McpToolClient connect(String url, String transport);

    /**
     * A client whose per-call timeout covers {@code requestTimeoutSeconds}: long-running tools need the
     * configured tool timeout (a multi-page SERP prepare ran for minutes behind the former sidecar). The default
     * ignores the value, so test fakes keep working; the Solon implementation applies it to the MCP
     * request AND to the HTTP socket read.
     */
    default McpToolClient connect(String url, String transport, int requestTimeoutSeconds) {
        return connect(url, transport);
    }
}
