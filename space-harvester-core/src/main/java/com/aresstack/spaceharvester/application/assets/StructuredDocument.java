package com.aresstack.spaceharvester.application.assets;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis einer strukturierten Extraktion (ADR 0026, Punkt 3) ohne die Blöcke selbst: Die gehen als Strom an einen
 * {@link StructureSink}, damit kein Text als Ganzes im Speicher liegt. Markdown und reiner Text sind Ansichten der Blöcke.
 *
 * @param head      Kopfdaten (Titel, bei Mails Betreff, Absender, Empfänger, Datum)
 * @param outcome   Status des Dokuments selbst
 * @param directory Verzeichnis der enthaltenen Dateien in Lesereihenfolge
 * @param blocks    Anzahl der Blöcke des äußeren Dokuments
 * @param chars     Zeichen in den Blöcken des äußeren Dokuments
 */
public record StructuredDocument(DocumentHead head, Outcome outcome, List<EmbeddedEntry> directory, long blocks,
                                 long chars) {

    public StructuredDocument {
        head = head == null ? DocumentHead.EMPTY : head;
        Objects.requireNonNull(outcome, "outcome");
        directory = List.copyOf(Objects.requireNonNull(directory, "directory"));
        if (blocks < 0 || chars < 0) {
            throw new IllegalArgumentException("Zähler dürfen nicht negativ sein");
        }
        if (directory.stream().map(EmbeddedEntry::id).distinct().count() != directory.size()) {
            throw new IllegalArgumentException("Teilkennungen im Verzeichnis doppelt");
        }
    }
}
