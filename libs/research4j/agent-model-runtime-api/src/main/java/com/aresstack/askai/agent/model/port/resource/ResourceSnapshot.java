package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A point-in-time view of the model resources behind the {@code ModelPort} (ADR 0001, 4.5): the endpoint
 * pool, the slot bindings, the capacity per model and endpoint, and the residency (loaded models). Immutable;
 * a caller that needs fresh data asks the port again.
 *
 * <p>The task executor uses it to decide what is worth dispatching next (for example to prefer tasks whose
 * slot is resident, or to see that two slots share one model). It never needs it for correctness: admission
 * is decided by {@code ModelPort.reserve}, not by reading this snapshot.</p>
 *
 * <p>{@code residencyKnown} is false when the platform could not query the endpoints' loaded models (for
 * example the endpoint was unreachable); {@link #getResidents()} is then empty without meaning "nothing
 * loaded".</p>
 */
public final class ResourceSnapshot {

    private final Instant capturedAt;
    private final List<ModelEndpoint> endpoints;
    private final List<SlotBinding> bindings;
    private final List<ModelCapacity> capacities;
    private final List<ResidentModel> residents;
    private final boolean residencyKnown;

    public ResourceSnapshot(Instant capturedAt, List<ModelEndpoint> endpoints, List<SlotBinding> bindings,
                            List<ModelCapacity> capacities, List<ResidentModel> residents,
                            boolean residencyKnown) {
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt must not be null");
        }
        this.capturedAt = capturedAt;
        this.endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
        this.capacities = capacities == null ? List.of() : List.copyOf(capacities);
        this.residents = residents == null ? List.of() : List.copyOf(residents);
        this.residencyKnown = residencyKnown;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public List<ModelEndpoint> getEndpoints() {
        return endpoints;
    }

    public List<SlotBinding> getBindings() {
        return bindings;
    }

    public List<ModelCapacity> getCapacities() {
        return capacities;
    }

    public List<ResidentModel> getResidents() {
        return residents;
    }

    public boolean isResidencyKnown() {
        return residencyKnown;
    }

    /** The binding of a slot, empty when the platform has no model configured for it. */
    public Optional<SlotBinding> bindingOf(ModelSlot slot) {
        for (SlotBinding binding : bindings) {
            if (binding.getSlot().equals(slot)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    /** The capacity entry of the model behind a slot, empty when the slot is unbound. */
    public Optional<ModelCapacity> capacityOf(ModelSlot slot) {
        Optional<SlotBinding> binding = bindingOf(slot);
        if (!binding.isPresent()) {
            return Optional.empty();
        }
        for (ModelCapacity capacity : capacities) {
            if (capacity.getEndpointId().equals(binding.get().getEndpointId())
                    && capacity.getModel().equals(binding.get().getModel())) {
                return Optional.of(capacity);
            }
        }
        return Optional.empty();
    }

    /** Whether the model behind a slot is reported as loaded; false when unbound or residency unknown. */
    public boolean isResident(ModelSlot slot) {
        Optional<SlotBinding> binding = bindingOf(slot);
        if (!binding.isPresent()) {
            return false;
        }
        for (ResidentModel resident : residents) {
            if (resident.getEndpointId().equals(binding.get().getEndpointId())
                    && resident.getModel().equals(binding.get().getModel())) {
                return true;
            }
        }
        return false;
    }
}
