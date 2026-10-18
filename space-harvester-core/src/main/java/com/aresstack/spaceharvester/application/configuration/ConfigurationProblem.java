package com.aresstack.spaceharvester.application.configuration;

import java.util.Objects;

/**
 * Ein Grund, aus dem eine Konfiguration nicht aktiviert wird, verständlich für die Oberfläche und für eine
 * REST-Antwort. Nennt nie den abgelehnten Wert, weil er ein Geheimnis sein kann.
 *
 * @param key     Property ohne Präfix {@code space-harvester.}, z. B. {@code ai.embedding-dimensions}; ein Feld der
 *                Oberfläche hängt sich daran
 * @param message deutscher Satz, was falsch ist oder fehlt
 */
public record ConfigurationProblem(String key, String message) {

    public ConfigurationProblem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
    }
}
