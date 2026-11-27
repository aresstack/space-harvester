package com.aresstack.askai.agent.model.port;

/**
 * A tool call the model requested in an assistant turn (ADR 0001, 3.2). The port reports it; it never runs
 * it. The caller decides whether the call is allowed (tool subset of the role, policy guards), executes it
 * and answers with a {@link ModelMessage#toolResult tool-result message} naming the same tool.
 *
 * <p>{@code id} is the provider's call id when the provider assigns one, otherwise "" (local models
 * frequently do not). {@code argumentsJson} is the arguments object as a raw JSON string, exactly as the
 * model produced it; small models can produce invalid JSON here, so callers validate before executing.</p>
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;

    /**
     * @param id            provider call id, null becomes ""
     * @param name          the called tool's name; non-blank
     * @param argumentsJson the raw arguments JSON; null or blank becomes "{}"
     */
    public ToolCall(String id, String name, String argumentsJson) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("tool call name must not be blank");
        }
        this.id = id == null ? "" : id;
        this.name = name;
        this.argumentsJson = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
    }

    /** The provider's call id, or "" when the provider assigns none. */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** The raw arguments JSON as the model produced it (not validated by the port). */
    public String getArgumentsJson() {
        return argumentsJson;
    }
}
