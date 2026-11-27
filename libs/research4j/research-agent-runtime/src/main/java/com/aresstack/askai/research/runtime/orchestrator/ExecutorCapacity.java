package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelCapacity;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The capacities the {@link TaskExecutor} dispatches against (ADR 0001, 4.5): the model capacity of the
 * worker role's slot (concurrent calls of that model on its endpoint, from the model port's scheduler) and,
 * as a separate capacity, the browser. The executor runs every ready task up to these numbers: on one GPU
 * with capacity 1 that is one task after another, with capacity 4 four at once, with several endpoints the
 * same code spreads over them.
 *
 * <p>Advisory like the scheduler it reads: the port admits the calls themselves, so a stale number can only
 * make a task wait in the scheduler, never overrun the model.</p>
 */
public interface ExecutorCapacity {

    /** The resource keys a task of {@code worker} holds while it runs. */
    List<Resource> resourcesOf(TaskWorker worker);

    /** The endpoint a task of {@code worker} is assigned to (recorded on the task; never empty). */
    String endpointOf(TaskWorker worker);

    /** One capacity: a key and how many tasks may hold it at once. */
    final class Resource {
        private final String key;
        private final int capacity;

        public Resource(String key, int capacity) {
            this.key = key;
            this.capacity = Math.max(1, capacity);
        }

        public String getKey() {
            return key;
        }

        public int getCapacity() {
            return capacity;
        }

        /** The model part of the key, for grouping ready tasks by model. */
        boolean isModel() {
            return key.startsWith("model:");
        }
    }

    /**
     * The productive capacities: the slot's binding and capacity from the port's resource view, and
     * {@code browserSlots} for browser-bound workers. A slot the port does not report counts with capacity 1
     * on an endpoint named {@code slot:<name>}.
     */
    static ExecutorCapacity fromPort(final ModelPort port, final int browserSlots) {
        return new ExecutorCapacity() {
            @Override
            public List<Resource> resourcesOf(TaskWorker worker) {
                List<Resource> resources = new ArrayList<Resource>();
                ModelSlot slot = worker.role().getSlot();
                ResourceSnapshot snapshot = port == null ? null : port.resources();
                SlotBinding binding = snapshot == null ? null : snapshot.bindingOf(slot).orElse(null);
                ModelCapacity capacity = snapshot == null ? null : snapshot.capacityOf(slot).orElse(null);
                String key = binding == null ? "model:slot:" + slot.getName()
                        : "model:" + binding.getEndpointId() + "/" + binding.getModel();
                resources.add(new Resource(key, capacity == null ? 1 : capacity.getMaxConcurrentCalls()));
                if (worker.needsBrowser()) {
                    resources.add(new Resource("browser", browserSlots));
                }
                return resources;
            }

            @Override
            public String endpointOf(TaskWorker worker) {
                ResourceSnapshot snapshot = port == null ? null : port.resources();
                SlotBinding binding = snapshot == null ? null
                        : snapshot.bindingOf(worker.role().getSlot()).orElse(null);
                return binding == null || binding.getEndpointId().isEmpty()
                        ? "slot:" + worker.role().getSlot().getName() : binding.getEndpointId();
            }
        };
    }
}
