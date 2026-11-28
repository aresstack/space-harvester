package com.aresstack.spaceharvester.mainframe.diagram;

/**
 * Port für die inhaltliche Kurzbeschreibung eines zusammengefassten Diagramm-Blocks (in MainframeMate
 * {@code SummarizerService} mit Stil LABEL). Aufrufe kommen aus der Diagramm-Erzeugung im Hintergrund und dürfen
 * blockieren; ein Fehler oder {@code null} lässt das heuristische Label stehen.
 */
public interface BlockLabelSummarizer {

    /** Ob die Zusammenfassung eingeschaltet ist; sonst entstehen keine Aufrufe. */
    boolean enabled();

    /**
     * @param code      roher Code des Blocks (ein Modell mit begrenztem Kontext zerlegt ihn selbst)
     * @param maxLength gewünschte Höchstlänge des Labels in Zeichen
     * @param purpose   was das Label beschreiben soll
     * @return kurzes Label oder {@code null}
     */
    String summarize(String code, int maxLength, String purpose);

    /**
     * Wie {@link #summarize}, aber als kurzer Satz für Menschen, die die Sprache nicht kennen (Stil SENTENCE statt
     * Substantiv-Phrase): was ein Block tut, was ein aufgerufenes Programm tut und wozu es aufgerufen wird.
     */
    default String explain(String code, int maxLength, String purpose) {
        return summarize(code, maxLength, purpose);
    }

    /**
     * Ausführliche Beschreibung eines zusammengefassten Blocks (Angelo 28.11.2026: „richtig detailliert beschreiben,
     * was da wirklich passiert“): mehrere einfache Sätze, Schritt für Schritt.
     */
    default String describe(String code, int maxLength, String purpose) {
        return explain(code, maxLength, purpose);
    }
}
