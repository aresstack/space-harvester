package com.aresstack.askai.mcp.api;

/**
 * A tool result: short, structured text for the model (never raw HTML/binary), or an error. Tool handlers
 * return errors as results — they do not throw across the MCP boundary.
 */
public final class McpToolResult {

    private final boolean error;
    private final String text;

    private McpToolResult(boolean error, String text) {
        this.error = error;
        this.text = text == null ? "" : text;
    }

    public static McpToolResult ok(String text) {
        return new McpToolResult(false, text);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(true, message);
    }

    public boolean isError() {
        return error;
    }

    public String getText() {
        return text;
    }
}
