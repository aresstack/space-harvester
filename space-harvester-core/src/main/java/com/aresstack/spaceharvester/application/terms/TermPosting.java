package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;

/**
 * Ein Begriff auf einer Seite, wie ihn der {@link TermIndex} ablegt: alle Fundstellen eines Wortlauts gleicher Art und
 * gleichen Suchwerts auf genau dieser Seite, mit der Herkunft des Ergebnisses.
 *
 * @param page             Quelle und Seite
 * @param kind             Art
 * @param term             Wortlaut der ersten Fundstelle auf der Seite
 * @param normalized       Suchwert
 * @param count            Zahl aller Vorkommen auf der Seite, auch über gekürzte Fundstellen hinaus
 * @param expansion        nur bei Akronymen: Ausschreibung direkt daneben oder {@code null}
 * @param section          Überschrift des Abschnitts der ersten Fundstelle oder {@code null}
 * @param occurrences      Fundstellen in Textreihenfolge
 * @param revision         Revision der Payload, aus der der Text stammt, oder {@code null}
 * @param inputSha256      Eingabe-Hash des Textes, auf den sich die Positionen beziehen
 * @param processorVersion Version des Prozessors {@code terms}
 * @param sortKey          eindeutiger Schlüssel in Index-Reihenfolge ({@link TermIndex#sortKey})
 */
public record TermPosting(PageKey page, TermKind kind, String term, String normalized, int count, String expansion,
                          String section, List<TermOccurrence> occurrences, String revision, String inputSha256,
                          String processorVersion, String sortKey) {

    public TermPosting {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(processorVersion, "processorVersion");
        Objects.requireNonNull(sortKey, "sortKey");
        occurrences = List.copyOf(occurrences);
    }

    /** Art und Suchwert: die Gruppe, unter der die globale Begriffsliste zusammenfasst. */
    public String groupKey() {
        return TermIndex.groupKey(normalized, kind);
    }
}
