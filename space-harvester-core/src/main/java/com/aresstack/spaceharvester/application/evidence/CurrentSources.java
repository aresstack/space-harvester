package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Port für die technische Prüfung eines Quellstellenbelegs ({@link SourceLocationCheck}): der jetzige Stand der
 * Dokumente, wie ihn die gespeicherte Programmanalyse kennt (ADR 0041, Punkte 10 und 11). Die Umsetzung liest nur über
 * das Tor der Programmanalyse und nur, was der Aufrufer lesen darf; nicht Lesbares ist für die Prüfung nicht vorhanden.
 */
public interface CurrentSources {

    /** Jetziger Stand eines Dokuments; leer, wenn es fehlt oder der Aufrufer es nicht lesen darf. */
    Optional<Current> document(DocumentKey document);

    /**
     * Jetzige Auflösung einer Abhängigkeit des Programms (Bibliotheksreihenfolge des Programms); leer, wenn sie jetzt
     * nicht gefunden wird.
     */
    Optional<Resolved> dependency(DocumentKey program, String name);

    /** Prozessor und Version, mit denen das Programm jetzt analysiert wird; leer, wenn keine Analyse vorliegt. */
    Optional<String> analysisVersion(DocumentKey program);

    /**
     * Jetziger Stand eines Dokuments.
     *
     * @param revision  Revision (Inhalts-Hash)
     * @param lineCount Zahl der Zeilen in dieser Revision
     */
    record Current(String revision, int lineCount) {

        public Current {
            Objects.requireNonNull(revision, "revision");
            if (lineCount < 0) {
                throw new IllegalArgumentException("Zeilenzahl " + lineCount + " ungültig");
            }
        }
    }

    /**
     * Jetzige Auflösung einer Abhängigkeit.
     *
     * @param document aufgelöstes Dokument
     * @param revision dessen Revision
     */
    record Resolved(DocumentKey document, String revision) {

        public Resolved {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(revision, "revision");
        }
    }
}
