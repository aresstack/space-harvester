package com.aresstack.askai.agent.model.port;

/**
 * Why a chat call failed. Every failure is NAMED and carries a human-readable, secret-free detail; none is
 * dressed up as a success. Callers map the kind onto their own honest status (for example a retry offer).
 */
public final class ModelFailure {

    public enum Kind {
        /** The platform has no model configured for the slot. */
        NO_BINDING,
        /** The endpoint is not reachable. */
        ENDPOINT_UNAVAILABLE,
        /** No capacity became free in time (implicit reservation only). */
        CAPACITY_TIMEOUT,
        /** The requested context exceeds the reservation, the model or the memory budget. */
        CONTEXT_TOO_LARGE,
        /** The reservation passed to the call is closed, revoked, for another slot, or already busy. */
        RESERVATION_INVALID,
        /** The call exceeded its time limit. */
        TIMEOUT,
        /** The caller cancelled the call (or closed its reservation). */
        CANCELLED,
        /** The provider answered with an error. */
        PROVIDER_ERROR,
        /** The provider's answer was unusable (malformed, empty, reasoning only). */
        INVALID_RESPONSE,
        /** The port is shutting down or was closed. */
        PORT_CLOSED
    }

    private final Kind kind;
    private final String detail;

    public ModelFailure(Kind kind, String detail) {
        if (kind == null) {
            throw new IllegalArgumentException("failure kind must not be null");
        }
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public Kind getKind() {
        return kind;
    }

    /** A human-readable, secret-free reason. */
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return detail.isEmpty() ? kind.name() : kind + ": " + detail;
    }
}
