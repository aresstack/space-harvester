package com.aresstack.askai.mcp.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A tool invocation: the tool name and its already-parsed arguments (flat, string-keyed). */
public final class McpToolCall {

    private final String toolName;
    private final Map<String, Object> arguments;

    public McpToolCall(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName == null ? "" : toolName;
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(
                arguments == null ? Collections.<String, Object>emptyMap() : arguments));
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public String getString(String name) {
        Object v = arguments.get(name);
        return v == null ? null : String.valueOf(v);
    }

    public long getInteger(String name, long defaultValue) {
        Object v = arguments.get(name);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? defaultValue : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        Object v = arguments.get(name);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v == null ? defaultValue : Boolean.parseBoolean(String.valueOf(v).trim());
    }
}
