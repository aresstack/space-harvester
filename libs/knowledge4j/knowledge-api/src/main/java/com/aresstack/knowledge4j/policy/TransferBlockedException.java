package com.aresstack.knowledge4j.policy;

/** Unchecked form of {@link TransferDeniedException} for exits whose interface declares no checked exception. */
public final class TransferBlockedException extends RuntimeException {

    public TransferBlockedException(TransferDeniedException denied) {
        super(denied.getMessage(), denied);
    }

    public TransferDeniedException denied() {
        return (TransferDeniedException) getCause();
    }
}
