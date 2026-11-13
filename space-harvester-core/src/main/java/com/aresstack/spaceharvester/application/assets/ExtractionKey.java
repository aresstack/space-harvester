package com.aresstack.spaceharvester.application.assets;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Schlüssel einer abgelegten strukturierten Extraktion (ADR 0026, Punkte 8 und 11): genau diese Bytes, diese
 * Extraktion in dieser Version und dieses Strukturmodell. Ändert sich eines davon, gilt die Ablage als alt und das
 * Original wird neu gelesen.
 *
 * @param sha256    Inhalt wie {@link Asset#sha256()}, also {@code sha256:<hex>}
 * @param extractor Kennung der Extraktion, z. B. {@code tika}
 * @param version   Version der Extraktion
 * @param model     Version des Strukturmodells, z. B. {@link DocumentStructure#MODEL_VERSION}
 */
public record ExtractionKey(String sha256, String extractor, String version, String model) {

    private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{64}");

    public ExtractionKey {
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256:<hex> erwartet");
        }
        requireText(extractor, "extractor");
        requireText(version, "version");
        requireText(model, "model");
    }

    /** Schlüssel mit dem aktuellen Strukturmodell. */
    public static ExtractionKey of(String sha256, DocumentStructure structure) {
        return new ExtractionKey(sha256, structure.id(), structure.version(), DocumentStructure.MODEL_VERSION);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
    }
}
