package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;

/**
 * Grenze einer Suche über ganze Teilbäume, deren Seiten keine Seitenmenge vollständig kennt (Angelo 26.11.2026): ein
 * SharePoint-Ordner, den der Katalog nicht führt, oder ein nur teilweise geladener Space. Die Einbindung einer Quelle
 * setzt keine vollständige Indexierung voraus (ADR 0037, 0038); die Grenze muss trotzdem greifen, bevor die Suche einen
 * Fund katalogisiert, lädt, bewertet oder an Nachbarn weiterreicht, nicht erst an der fertigen Liste.
 *
 * <p>Mit Grenze gehört eine Seite zur Suche, wenn sie in der Seitenmenge der Anfrage steht oder die Grenze sie
 * zulässt ({@link KeywordSearch.Query#admits(String, String)}). Die Grenze entscheidet nach der Lage der Seite in der
 * Quelle; wie sie das klärt, ist ihre Sache (etwa über die gemeinsame Navigation).
 */
public interface SearchBoundary {

    /** Ob die Seite innerhalb der Grenze liegt; lässt sich das nicht klären, {@code false}. */
    boolean admits(PageKey key);

    /** Ob die Quelle Seiten innerhalb der Grenze haben kann. */
    boolean reaches(String sourceId);

    /** Ein stabiler Fingerabdruck der Grenze für Cursor; eine andere Grenze macht den Cursor ungültig. */
    String fingerprint();
}
