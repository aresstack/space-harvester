package com.aresstack.askai.mcp.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A single tool: name, description, flat parameters and its handler. */
public final class McpToolContribution {

    private final String name;
    private final String description;
    private final List<McpToolParameter> parameters;
    private final McpToolHandler handler;

    public McpToolContribution(String name, String description, List<McpToolParameter> parameters,
                               McpToolHandler handler) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("tool name must not be empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("tool handler must not be null");
        }
        this.name = name.trim();
        this.description = description == null ? "" : description;
        this.parameters = Collections.unmodifiableList(new ArrayList<McpToolParameter>(
                parameters == null ? Collections.<McpToolParameter>emptyList() : parameters));
        this.handler = handler;
    }

    public static McpToolContribution of(String name, String description, McpToolHandler handler,
                                         McpToolParameter... parameters) {
        return new McpToolContribution(name, description, Arrays.asList(parameters), handler);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<McpToolParameter> getParameters() {
        return parameters;
    }

    public McpToolHandler getHandler() {
        return handler;
    }
}
