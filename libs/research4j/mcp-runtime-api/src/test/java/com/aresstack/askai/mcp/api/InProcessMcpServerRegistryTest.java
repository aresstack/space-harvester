package com.aresstack.askai.mcp.api;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The transport-free MCP registry: endpoints, tokens, dynamic tools, dispatch, idempotent shutdown. */
public class InProcessMcpServerRegistryTest {

    private static McpEndpointDefinition def() {
        return new McpEndpointDefinition("test.endpoint", "Test");
    }

    private static McpToolCall call(String tool, String key, Object value) {
        Map<String, Object> args = new HashMap<String, Object>();
        if (key != null) {
            args.put(key, value);
        }
        return new McpToolCall(tool, args);
    }

    @Test
    public void endpointRegistersAndPingIsDiscoverableAndCallable() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Arrays.asList(McpTestTools.ping(), McpTestTools.echo()));

        assertTrue(registry.listToolNames("test.endpoint", handle.getToken()).contains("ping"));
        McpToolResult r = registry.invoke("test.endpoint", handle.getToken(), call("ping", null, null));
        assertFalse(r.isError());
        assertEquals("pong", r.getText());
    }

    @Test
    public void echoReturnsItsArgument() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.echo()));
        McpToolResult r = registry.invoke("test.endpoint", handle.getToken(), call("echo", "text", "hi there"));
        assertEquals("hi there", r.getText());
    }

    @Test
    public void toolsCanBeAddedAndRemovedDynamicallyWithNotification() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final AtomicInteger changes = new AtomicInteger();
        registry.addToolsChangedListener(new InProcessMcpServerRegistry.ToolsChangedListener() {
            public void onToolsChanged(String endpointId) {
                changes.incrementAndGet();
            }
        });
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        assertEquals(1, registry.listToolNames("test.endpoint", handle.getToken()).size());
        registry.updateTools(handle, Arrays.asList(McpTestTools.ping(), McpTestTools.echo()));
        assertEquals(2, registry.listToolNames("test.endpoint", handle.getToken()).size());
        registry.updateTools(handle, Collections.<McpToolContribution>emptyList());
        assertTrue(registry.listToolNames("test.endpoint", handle.getToken()).isEmpty());
        assertEquals(3, changes.get());
    }

    @Test
    public void unknownToolIsRejectedControlled() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        McpToolResult r = registry.invoke("test.endpoint", handle.getToken(), call("nope", null, null));
        assertTrue(r.isError());
    }

    @Test
    public void wrongTokenIsRejected() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        assertTrue(registry.invoke("test.endpoint", "wrong-token", call("ping", null, null)).isError());
        assertTrue(registry.listToolNames("test.endpoint", "wrong-token").isEmpty());
    }

    @Test
    public void tokensAreDistinctPerEndpoint() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle a = registry.registerEndpoint(new McpEndpointDefinition("a", "A"));
        McpEndpointHandle b = registry.registerEndpoint(new McpEndpointDefinition("b", "B"));
        assertFalse(a.getToken().equals(b.getToken()));
        // A's token cannot access B.
        assertTrue(registry.listToolNames("b", a.getToken()).isEmpty());
    }

    @Test
    public void unregisterInvalidatesTheEndpoint() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        registry.unregisterEndpoint(handle);
        assertFalse(registry.isEndpointRegistered("test.endpoint"));
        assertTrue(registry.invoke("test.endpoint", handle.getToken(), call("ping", null, null)).isError());
    }

    @Test
    public void shutdownIsIdempotentAndEndpointsAreGone() {
        InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        McpEndpointHandle handle = registry.registerEndpoint(def());
        registry.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        registry.shutdown();
        registry.shutdown(); // idempotent
        assertFalse(registry.isEndpointRegistered("test.endpoint"));
        assertTrue(registry.invoke("test.endpoint", handle.getToken(), call("ping", null, null)).isError());
    }
}
