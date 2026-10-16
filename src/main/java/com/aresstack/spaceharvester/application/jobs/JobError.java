package com.aresstack.spaceharvester.application.jobs;

import java.util.Objects;

/**
 * Grund, aus dem ein Auftrag gescheitert ist.
 *
 * @param code    maschinenlesbar, z. B. {@code TIMEOUT}, {@code INTERRUPTED}, {@code SOURCE_UNAVAILABLE}
 * @param message für API-Antworten bestimmt; enthält keine Zugangsdaten
 */
public record JobError(String code, String message) {

    public JobError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
