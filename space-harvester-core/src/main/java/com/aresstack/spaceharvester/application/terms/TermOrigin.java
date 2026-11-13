package com.aresstack.spaceharvester.application.terms;

import java.util.Locale;

/**
 * Herkunft eines Eintrags im {@link TermIndex} (ADR 0026, AP9b): die Seite selbst oder ein Anhang beziehungsweise eine
 * enthaltene Datei. Zählungen und alles, was aus Häufigkeiten folgt (Kandidaten für Glossar, Synonyme, Akronyme),
 * bleiben bei {@link #PAGES}; ein großer Anhang überschwemmt sie so nicht. Fundstellen in Dokumenten weist nur aus, wer
 * {@link #DOCUMENTS} oder {@link #ALL} verlangt.
 */
public enum TermOrigin {

    /** Nur Seiten; Vorgabe, wie vor AP9b. */
    PAGES,
    /** Nur Anhänge und enthaltene Dateien. */
    DOCUMENTS,
    /** Beides, getrennt gezählt. */
    ALL;

    /** Ob ein Eintrag dieser Herkunft dazugehört. */
    public boolean includes(boolean document) {
        return this == ALL || (this == DOCUMENTS) == document;
    }

    /** Kennung in REST: {@code pages}, {@code documents}, {@code all}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException bei unbekannter Kennung */
    public static TermOrigin of(String id) {
        for (TermOrigin origin : values()) {
            if (origin.id().equals(id)) {
                return origin;
            }
        }
        throw new IllegalArgumentException("Unbekannte Herkunft: " + id);
    }
}
