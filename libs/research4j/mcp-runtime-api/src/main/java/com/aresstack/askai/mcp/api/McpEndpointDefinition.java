package com.aresstack.askai.mcp.api;

/** Describes a logical MCP endpoint to register (a stable id + a human-readable name). */
public final class McpEndpointDefinition {

    private final String endpointId;
    private final String displayName;

    public McpEndpointDefinition(String endpointId, String displayName) {
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new IllegalArgumentException("endpointId must not be empty");
        }
        this.endpointId = endpointId.trim();
        this.displayName = displayName == null ? this.endpointId : displayName;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getDisplayName() {
        return displayName;
    }
}
