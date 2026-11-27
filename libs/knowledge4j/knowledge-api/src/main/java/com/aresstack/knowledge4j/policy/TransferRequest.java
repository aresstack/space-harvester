package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.source.Provenance;

import java.util.Objects;

/**
 * What the policy sees: the exit, the kind of payload, its provenance (already merged with the run's high-water
 * mark) and its size. Never the payload itself.
 */
public record TransferRequest(TransferTarget target, PayloadKind kind, Provenance provenance, long payloadChars) {

    public TransferRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        provenance = provenance == null ? Provenance.unknown() : provenance;
        if (payloadChars < 0) {
            throw new IllegalArgumentException("payload size must not be negative");
        }
    }
}
