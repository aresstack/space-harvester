package com.aresstack.spaceharvester.application.enrichment;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis eines Prozessors.
 *
 * @param payloadJson JSON-Objekt mit den abgeleiteten Hinweisen; Aufbau legt der Prozessor je Version fest
 * @param warnings    Hinweise zur Qualität, z. B. gekürzte Listen
 * @param version     Version, mit der das Ergebnis entstand, oder {@code null} für {@link EnrichmentProcessor#version()};
 *                    nötig, wenn der Prozessor währenddessen wechseln kann (Embedding-Modell, ADR 0015)
 */
public record EnrichmentOutput(String payloadJson, List<String> warnings, String version) {

    public EnrichmentOutput {
        payloadJson = Objects.requireNonNull(payloadJson, "payloadJson").trim();
        if (!payloadJson.startsWith("{") || !payloadJson.endsWith("}")) {
            throw new IllegalArgumentException("Ergebnis muss ein JSON-Objekt sein");
        }
        warnings = List.copyOf(warnings);
    }

    public EnrichmentOutput(String payloadJson, List<String> warnings) {
        this(payloadJson, warnings, null);
    }

    public static EnrichmentOutput of(String payloadJson) {
        return new EnrichmentOutput(payloadJson, List.of());
    }
}
