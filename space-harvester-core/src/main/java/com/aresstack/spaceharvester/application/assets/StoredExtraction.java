package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.Objects;

/**
 * Eine abgelegte strukturierte Extraktion (ADR 0026, Punkte 8 und 11) ohne ihre Blöcke: die liest {@link
 * ExtractionStore#sections} abschnittsweise.
 *
 * @param key       Bytes, Extraktion und Strukturmodell
 * @param document  Kopfdaten, Status und Verzeichnis der enthaltenen Dateien
 * @param sections  Anzahl der Abschnitte
 * @param blocks    Anzahl der Blöcke
 * @param chars     Zeichen in allen Blöcken
 * @param createdAt Zeitpunkt der Ablage
 */
public record StoredExtraction(ExtractionKey key, StructuredDocument document, int sections, long blocks, long chars,
                               Instant createdAt) {

    public StoredExtraction {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sections < 0 || blocks < 0 || chars < 0) {
            throw new IllegalArgumentException("Zähler dürfen nicht negativ sein");
        }
    }
}
