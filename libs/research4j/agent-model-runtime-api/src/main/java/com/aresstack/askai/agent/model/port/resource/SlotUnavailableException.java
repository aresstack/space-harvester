package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * Why a slot reservation could not be granted. Every reason is named; none is reported as a success.
 */
public final class SlotUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The platform has no model configured for the slot. */
        NO_BINDING,
        /** The endpoint serving the slot is not reachable. */
        ENDPOINT_UNAVAILABLE,
        /** The context size exceeds what the model or the endpoint's memory budget allows. */
        CONTEXT_TOO_LARGE,
        /** No place became free within the request's {@code maxWait}. */
        WAIT_TIMEOUT,
        /** The port is shutting down or was closed. */
        PORT_CLOSED
    }

    private final ModelSlot slot;
    private final Reason reason;

    public SlotUnavailableException(ModelSlot slot, Reason reason, String detail) {
        super(reason + " for slot " + slot + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        this.slot = slot;
        this.reason = reason;
    }

    public ModelSlot getSlot() {
        return slot;
    }

    public Reason getReason() {
        return reason;
    }
}
