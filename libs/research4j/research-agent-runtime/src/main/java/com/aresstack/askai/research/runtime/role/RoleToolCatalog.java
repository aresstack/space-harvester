package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tool subset a {@link ResearchRole} may use (ADR 0001, 3.3 and 4.2: least privilege per role). The role
 * declares tool NAMES; the concrete {@link McpToolContribution}s come from the host per session and per
 * state, because visibility depends on the live phase and run state ({@code ResearchToolPolicy}). Binding
 * the catalog to what the host offers right now therefore yields the role's actual tool list: the
 * intersection, in the role's declared order. A declared tool the host does not offer is simply absent, it
 * is never synthesized, and a tool outside the catalog is never handed to the role.
 *
 * <p>The bound contributions are dispatched in-process through {@code ContributionToolInvoker}; for a model
 * call with native tool calling {@link #toolDefinitions} turns them into the model port's MCP-free
 * {@link ToolDefinition}s.</p>
 */
public final class RoleToolCatalog {

    private static final RoleToolCatalog NONE = new RoleToolCatalog(Collections.<String>emptyList());

    private final List<String> toolNames;

    private RoleToolCatalog(List<String> toolNames) {
        Set<String> unique = new LinkedHashSet<String>();
        for (String name : toolNames) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("tool names must not be blank");
            }
            unique.add(name.trim());
        }
        this.toolNames = Collections.unmodifiableList(new ArrayList<String>(unique));
    }

    /** A role without tools (a pure judgement or generation call). */
    public static RoleToolCatalog none() {
        return NONE;
    }

    public static RoleToolCatalog of(String... toolNames) {
        return toolNames.length == 0 ? NONE : new RoleToolCatalog(java.util.Arrays.asList(toolNames));
    }

    public static RoleToolCatalog of(List<String> toolNames) {
        return toolNames == null || toolNames.isEmpty() ? NONE : new RoleToolCatalog(toolNames);
    }

    /** The declared tool names, in order. */
    public List<String> getToolNames() {
        return toolNames;
    }

    public boolean isEmpty() {
        return toolNames.isEmpty();
    }

    public boolean allows(String toolName) {
        return toolName != null && toolNames.contains(toolName.trim());
    }

    /**
     * The role's tools among what the host offers now: the declared names that {@code offered} contains, in
     * the catalog's order. Never more than the catalog, never a tool the host did not offer.
     */
    public List<McpToolContribution> select(List<McpToolContribution> offered) {
        if (offered == null || offered.isEmpty() || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpToolContribution> selected = new ArrayList<McpToolContribution>();
        for (String name : toolNames) {
            for (McpToolContribution contribution : offered) {
                if (contribution != null && name.equals(contribution.getName())) {
                    selected.add(contribution);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(selected);
    }

    /** {@link #select} as model-port tool definitions (name, description, argument schema). */
    public List<ToolDefinition> toolDefinitions(List<McpToolContribution> offered) {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        for (McpToolContribution contribution : select(offered)) {
            definitions.add(toDefinition(contribution));
        }
        return Collections.unmodifiableList(definitions);
    }

    /** One contribution as a tool definition; the flat MCP parameters become a JSON object schema. */
    static ToolDefinition toDefinition(McpToolContribution contribution) {
        StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        List<String> required = new ArrayList<String>();
        boolean first = true;
        for (McpToolParameter parameter : contribution.getParameters()) {
            if (!first) {
                schema.append(',');
            }
            first = false;
            schema.append(quote(parameter.getName())).append(":{\"type\":")
                    .append(quote(jsonType(parameter)));
            if (!parameter.getDescription().isEmpty()) {
                schema.append(",\"description\":").append(quote(parameter.getDescription()));
            }
            if (!parameter.getEnumValues().isEmpty()) {
                schema.append(",\"enum\":[");
                for (int i = 0; i < parameter.getEnumValues().size(); i++) {
                    if (i > 0) {
                        schema.append(',');
                    }
                    schema.append(quote(parameter.getEnumValues().get(i)));
                }
                schema.append(']');
            }
            schema.append('}');
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }
        schema.append('}');
        if (!required.isEmpty()) {
            schema.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) {
                    schema.append(',');
                }
                schema.append(quote(required.get(i)));
            }
            schema.append(']');
        }
        schema.append('}');
        return new ToolDefinition(contribution.getName(), contribution.getDescription(), schema.toString());
    }

    private static String jsonType(McpToolParameter parameter) {
        switch (parameter.getType()) {
            case INTEGER:
                return "integer";
            case BOOLEAN:
                return "boolean";
            default:
                return "string"; // STRING and ENUM (an enum is a string with allowed values)
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public String toString() {
        return toolNames.toString();
    }
}
