package com.aresstack.enterpriseai.proasteion.katagogion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ein einzelnes Tool: Name, Beschreibung, flache Parameter und der ausführende Handler.
 *
 * <p>Herkunft: askai-java8 {@code McpToolContribution}; neu: MCP-Namensregel für Tool-Namen geprüft,
 * doppelte Parameternamen abgelehnt.
 */
public final class McpToolContribution {

    private final String name;
    private final String description;
    private final List<McpToolParameter> parameters;
    private final McpToolHandler handler;

    public McpToolContribution(String name, String description, List<McpToolParameter> parameters,
                               McpToolHandler handler) {
        if (name == null || !isValidToolName(name.trim())) {
            throw new IllegalArgumentException("tool name must be 1-128 characters of A-Z, a-z, 0-9, '_', '-', '.'");
        }
        if (handler == null) {
            throw new IllegalArgumentException("tool handler must not be null");
        }
        List<McpToolParameter> copy = new ArrayList<McpToolParameter>();
        Set<String> names = new HashSet<String>();
        if (parameters != null) {
            for (McpToolParameter parameter : parameters) {
                if (parameter == null) {
                    throw new IllegalArgumentException("tool parameter must not be null");
                }
                if (!names.add(parameter.getName())) {
                    throw new IllegalArgumentException("duplicate tool parameter: " + parameter.getName());
                }
                copy.add(parameter);
            }
        }
        this.name = name.trim();
        this.description = description == null ? "" : description;
        this.parameters = Collections.unmodifiableList(copy);
        this.handler = handler;
    }

    public static McpToolContribution of(String name, String description, McpToolHandler handler,
                                         McpToolParameter... parameters) {
        return new McpToolContribution(name, description, Arrays.asList(parameters), handler);
    }

    /** MCP-Namensregel für Tools: 1–128 ASCII-Buchstaben, Ziffern, {@code _}, {@code -}, {@code .}. */
    public static boolean isValidToolName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
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

    @Override
    public String toString() {
        return "McpToolContribution[" + name + parameters + "]";
    }
}
