package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * One reserved place of a model's capacity on an endpoint (ADR 0001, 4.5), granted by
 * {@code ModelPort.reserve}. While it is active the scheduler counts it against the capacity of its model
 * and endpoint; calls made under it run without further queueing.
 *
 * <p>A reservation covers any number of SEQUENTIAL calls (for example every turn of one worker's tool
 * loop), never concurrent ones: a second call while one is running under the same reservation fails. It
 * pins the resolution of the slot for its lifetime, so all calls under it go to the same endpoint and
 * model even if the configuration changes meanwhile.</p>
 *
 * <p>{@link #close()} releases the place; it is idempotent, never throws, and cancels a call still running
 * under it. Callers use try-with-resources. The platform may also revoke a reservation (endpoint removed,
 * port shut down); {@link #isActive()} then turns false and later calls under it fail.</p>
 */
public interface SlotReservation extends AutoCloseable {

    /** A unique id for diagnostics and receipts. */
    String getId();

    ModelSlot getSlot();

    /** The endpoint the slot was resolved to (the "assigned endpoint" of a task, ADR 0001, 4.4). */
    String getEndpointId();

    ModelHandle getModel();

    /** The effective context size in tokens (the requested size, or the slot default). */
    int getContextTokens();

    /** True until closed or revoked. */
    boolean isActive();

    /** Releases the place; idempotent, never throws. */
    @Override
    void close();
}
