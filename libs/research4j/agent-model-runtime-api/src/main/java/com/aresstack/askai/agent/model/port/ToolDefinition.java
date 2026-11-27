package com.aresstack.askai.agent.model.port;

/**
 * One tool offered to the model in a chat call (ADR 0001, 3.2): a name, a description the model reads, and
 * the JSON schema of its arguments as a raw JSON object string. The port forwards the schema verbatim to
 * the model; it neither parses nor validates it, and it never executes a tool. Executing a tool call is the
 * caller's job (in-process through {@code ContributionToolInvoker}, the policy guards included), which then
 * sends the outcome back as a {@link ModelMessage#toolResult tool-result message}.
 *
 * <p>A role's catalogue is typically derived from its {@code McpToolContribution}s; this type stays
 * MCP-free so the model port does not depend on the MCP API.</p>
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersSchemaJson;

    /**
     * @param name                 the tool name the model calls; non-blank
     * @param description          what the tool does, for the model; null becomes ""
     * @param parametersSchemaJson the JSON schema of the arguments object; null or blank becomes
     *                             {@code {"type":"object","properties":{}}} (a tool without arguments)
     */
    public ToolDefinition(String name, String description, String parametersSchemaJson) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.parametersSchemaJson = parametersSchemaJson == null || parametersSchemaJson.trim().isEmpty()
                ? "{\"type\":\"object\",\"properties\":{}}"
                : parametersSchemaJson;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** The arguments schema as a raw JSON object string, forwarded verbatim. */
    public String getParametersSchemaJson() {
        return parametersSchemaJson;
    }
}
