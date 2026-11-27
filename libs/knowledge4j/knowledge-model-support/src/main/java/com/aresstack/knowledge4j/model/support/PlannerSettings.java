package com.aresstack.knowledge4j.model.support;

import com.aresstack.knowledge4j.model.ModelSlot;
import com.aresstack.knowledge4j.model.resource.ModelEndpoint;
import com.aresstack.knowledge4j.model.resource.ModelHandle;
import com.aresstack.knowledge4j.model.resource.SlotBinding;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The host's settings for the planner, read on every request so a changed setting applies to the next
 * reservation. Nothing here has a built-in default: every value comes from the host's own settings.
 */
public interface PlannerSettings {

    /** The model, endpoint and context size each slot is bound to; the context size is always positive. */
    List<SlotBinding> bindings();

    /** The endpoints for diagnostics; an endpoint missing here is reported as remote and reachable. */
    List<ModelEndpoint> endpoints();

    /** How many calls may run at once on this model on this endpoint; at least 1. */
    int capacity(String endpointId, ModelHandle model);

    /**
     * How long a call with an implicit reservation may wait for a place on this slot; never negative.
     * {@link Duration#ZERO} means "only if free now". An explicit reservation brings its own wait.
     */
    Duration maxWait(ModelSlot slot);

    default Optional<SlotBinding> binding(ModelSlot slot) {
        for (SlotBinding binding : bindings()) {
            if (binding.getSlot().equals(slot)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }
}
