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

    /** Die Teile im gültigen Stand, in der Reihenfolge ihres Beginns; leer, wenn die Extraktion fehlt (AP7b). */
    java.util.List<StoredPart> parts(ExtractionKey key);

    /**
     * Die Abschnitte eines Teils in Reihenfolge, einzeln gelesen; der Strom muss geschlossen werden. Leer, wenn
     * Extraktion oder Teil fehlen.
     */
    Stream<TextSection> partSections(ExtractionKey key, String partKey);

    /**
     * Die Abschnitte aller Teile, Teil für Teil in der Reihenfolge von {@link #parts}, mit einmal gelesenem Stand; der
     * Strom muss geschlossen werden. Leer, wenn die Extraktion fehlt.
     */
    Stream<TextSection> allPartSections(ExtractionKey key);

    /**
     * Die Teile eines Stands mit einmal gelesenem Manifest (ADR 0026, AP4b): je Teil seine Abschnitte, einzeln gelesen.
     * Leer, wenn die Extraktion fehlt.
     */
    default Optional<PartSections> partSections(ExtractionKey key) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        parts(key).forEach(part -> keys.add(part.key()));
        return find(key).map(found -> new PartSections() {
            @Override
            public java.util.Set<String> keys() {
                return keys;
            }

            @Override
            public Stream<TextSection> sections(String partKey) {
                return partSections(key, partKey);
            }
        });
    }

    /** Die Teile eines Stands; {@link #sections} liest die Abschnitte eines Teils einzeln, solange der Strom läuft. */
    interface PartSections {

        /** Schlüssel der abgelegten Teile in der Reihenfolge ihres Beginns. */
        java.util.Set<String> keys();

        /** Die Abschnitte eines Teils; leer für einen unbekannten Teil. Der Strom muss geschlossen werden. */
        Stream<TextSection> sections(String partKey);
    }
}
