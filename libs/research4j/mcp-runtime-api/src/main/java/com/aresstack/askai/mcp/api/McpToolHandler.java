package com.aresstack.askai.mcp.api;

/** Executes a tool call. Implementations must return a result (never throw across the boundary). */
public interface McpToolHandler {
    McpToolResult invoke(McpToolCall call);
}
