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
     * @param code      roher Code des Blocks (höchstens etwa 2 KB)
     * @param maxLength gewünschte Höchstlänge des Labels in Zeichen
     * @param purpose   was das Label beschreiben soll
     * @return kurzes Label oder {@code null}
     */
    String summarize(String code, int maxLength, String purpose);
}
