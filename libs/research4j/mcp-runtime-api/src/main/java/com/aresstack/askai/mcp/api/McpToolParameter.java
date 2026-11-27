package com.aresstack.askai.mcp.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One flat tool parameter: name, type, required flag, description, and (for ENUM) the allowed values. */
public final class McpToolParameter {

    private final String name;
    private final McpToolType type;
    private final boolean required;
    private final String description;
    private final List<String> enumValues;

    private McpToolParameter(String name, McpToolType type, boolean required, String description,
                            List<String> enumValues) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("parameter name must not be empty");
        }
        this.name = name.trim();
        this.type = type == null ? McpToolType.STRING : type;
        this.required = required;
        this.description = description == null ? "" : description;
        this.enumValues = Collections.unmodifiableList(new ArrayList<String>(
                enumValues == null ? Collections.<String>emptyList() : enumValues));
    }

    public static McpToolParameter string(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.STRING, required, description, null);
    }

    public static McpToolParameter integer(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.INTEGER, required, description, null);
    }

    public static McpToolParameter bool(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.BOOLEAN, required, description, null);
    }

    public static McpToolParameter enumeration(String name, boolean required, String description,
                                               List<String> values) {
        return new McpToolParameter(name, McpToolType.ENUM, required, description, values);
    }

    public String getName() {
        return name;
    }

    public McpToolType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }
}
