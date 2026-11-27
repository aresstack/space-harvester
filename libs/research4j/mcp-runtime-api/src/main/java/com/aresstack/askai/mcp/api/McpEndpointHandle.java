package com.aresstack.askai.mcp.api;

/**
 * A live endpoint registration. Carries the endpoint id and a random, non-guessable session token that a
 * caller must present. A transport adapter also exposes the local URL; the in-process reference registry uses
 * only the token. The token is invalidated when the endpoint is unregistered.
 */
public final class McpEndpointHandle {

    private final String endpointId;
    private final String token;

    public McpEndpointHandle(String endpointId, String token) {
        this.endpointId = endpointId;
        this.token = token;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getToken() {
        return token;
    }
}
