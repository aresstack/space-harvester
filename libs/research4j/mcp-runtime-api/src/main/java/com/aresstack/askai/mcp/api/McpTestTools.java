package com.aresstack.askai.mcp.api;

/** The two neutral test tools required by the runtime foundation: {@code ping()} and {@code echo(text)}. */
public final class McpTestTools {

    private McpTestTools() {
    }

    public static McpToolContribution ping() {
        return McpToolContribution.of("ping", "Liveness check; returns \"pong\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok("pong");
                    }
                });
    }

    public static McpToolContribution echo() {
        return McpToolContribution.of("echo", "Echoes the given text back.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String text = call.getString("text");
                        return McpToolResult.ok(text == null ? "" : text);
                    }
                },
                McpToolParameter.string("text", true, "The text to echo"));
    }
}
