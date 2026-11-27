package com.aresstack.askai.agent.model.port.resource;

/**
 * CAPACITY of one model on one endpoint (ADR 0001, 4.5): the maximum number of concurrent calls, and how
 * much of it is in use right now.
 *
 * <p>{@code maxConcurrentCalls} is a user setting with default 1 that mirrors the endpoint's own parallelism
 * (for Ollama {@code OLLAMA_NUM_PARALLEL}); the measured value for a machine is entered by the user. The
 * scheduler never admits more concurrent reservations than this, so nothing waits invisibly in the
 * provider's own queue. {@code waitingReservations} counts requests queued in the scheduler for it.</p>
 */
public final class ModelCapacity {

    private final String endpointId;
    private final ModelHandle model;
    private final int maxConcurrentCalls;
    private final int activeReservations;
    private final int waitingReservations;

    public ModelCapacity(String endpointId, ModelHandle model, int maxConcurrentCalls,
                         int activeReservations, int waitingReservations) {
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be blank");
        }
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (activeReservations < 0 || waitingReservations < 0) {
            throw new IllegalArgumentException("reservation counts must be >= 0");
        }
        this.endpointId = endpointId;
        this.model = model;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.activeReservations = activeReservations;
        this.waitingReservations = waitingReservations;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public ModelHandle getModel() {
        return model;
    }

    /** The configured capacity (setting, default 1). */
    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public int getActiveReservations() {
        return activeReservations;
    }

    public int getWaitingReservations() {
        return waitingReservations;
    }

    /** Free places right now ({@code max - active}, never negative). */
    public int getFreeCapacity() {
        return Math.max(0, maxConcurrentCalls - activeReservations);
    }
}
