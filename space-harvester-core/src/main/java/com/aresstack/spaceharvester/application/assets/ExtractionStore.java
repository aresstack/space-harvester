package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Ablage strukturierter Extraktionen in Abschnitten (ADR 0026, Punkte 8 und 11), je Inhalt, Extraktion und
 * Strukturmodell. Kein Text liegt als Ganzes im Speicher: geschrieben wird fortlaufend, gelesen abschnittsweise. Die
 * bisherige Ablage {@link AssetText} bleibt daneben unverändert lesbar.
 */
public interface ExtractionStore {

    /** Vorgeschlagene Höchstgröße eines Abschnitts in Zeichen. */
    int DEFAULT_SECTION_CHARS = 100_000;

    /** Die abgelegte Extraktion genau dieses Schlüssels. */
    Optional<StoredExtraction> find(ExtractionKey key);

    /**
     * Beginnt eine neue Extraktion; die vorhandene gilt, bis sie bestätigt ist.
     *
     * @param sectionChars Höchstgröße eines Abschnitts in Zeichen, mindestens 1
     */
    ExtractionWriter writer(ExtractionKey key, int sectionChars);

    /** Wie {@link #writer(ExtractionKey, int)} mit {@link #DEFAULT_SECTION_CHARS}. */
    default ExtractionWriter writer(ExtractionKey key) {
        return writer(key, DEFAULT_SECTION_CHARS);
    }

    /** Ein Abschnitt; leer, wenn die Extraktion oder der Abschnitt fehlt. */
    Optional<TextSection> section(ExtractionKey key, int index);

    /**
     * Alle Abschnitte in Reihenfolge, einzeln gelesen, solange der Strom läuft; der Strom muss geschlossen werden.
     * Leer, wenn die Extraktion fehlt.
     */
    Stream<TextSection> sections(ExtractionKey key);
}
