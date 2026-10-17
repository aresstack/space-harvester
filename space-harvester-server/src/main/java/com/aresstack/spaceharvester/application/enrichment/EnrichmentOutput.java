package com.aresstack.spaceharvester.application.enrichment;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis eines Prozessors.
 *
 * @param payloadJson JSON-Objekt mit den abgeleiteten Hinweisen; Aufbau legt der Prozessor je Version fest
 * @param warnings    Hinweise zur Qualität, z. B. gekürzte Listen
 */
public record EnrichmentOutput(String payloadJson, List<String> warnings) {

    public EnrichmentOutput {
        payloadJson = Objects.requireNonNull(payloadJson, "payloadJson").trim();
        if (!payloadJson.startsWith("{") || !payloadJson.endsWith("}")) {
            throw new IllegalArgumentException("Ergebnis muss ein JSON-Objekt sein");
        }
        warnings = List.copyOf(warnings);
    }

    public static EnrichmentOutput of(String payloadJson) {
        return new EnrichmentOutput(payloadJson, List.of());
    }
}
