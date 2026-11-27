package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * How the platform currently resolves one {@link ModelSlot}: to which endpoint and which model (as an
 * opaque {@link ModelHandle}), with the model's context size (ADR 0001, 3.2 and 4.5; plan 4.1, rule 1).
 * Slots with equal handles on the same endpoint share one model and therefore one capacity and one context.
 */
public final class SlotBinding {

    private final ModelSlot slot;
    private final String endpointId;
    private final ModelHandle model;
    private final int contextTokens;
    private final String modelLabel;

    /**
     * @param slot          the slot; non-null
     * @param endpointId    the endpoint serving it; non-blank
     * @param model         the model filling it; non-null
     * @param contextTokens the context ({@code num_ctx}) every call on this model sends; positive
     * @param modelLabel    a display label of the bound model for logs and diagnostics; null = empty
     */
    public SlotBinding(ModelSlot slot, String endpointId, ModelHandle model, int contextTokens,
                       String modelLabel) {
        if (slot == null || model == null) {
            throw new IllegalArgumentException("slot and model must not be null");
        }
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be blank");
        }
        if (contextTokens < 1) {
            throw new IllegalArgumentException("context tokens must be >= 1");
        }
        this.slot = slot;
        this.endpointId = endpointId;
        this.model = model;
        this.contextTokens = contextTokens;
        this.modelLabel = modelLabel == null ? "" : modelLabel;
    }

    public ModelSlot getSlot() {
        return slot;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public ModelHandle getModel() {
        return model;
    }

    /** The context ({@code num_ctx}) of the bound model in tokens: every call on it sends this value. */
    public int getContextTokens() {
        return contextTokens;
    }

    /** A display label of the bound model (for logs and diagnostics, never for routing). */
    public String getModelLabel() {
        return modelLabel;
    }
}
