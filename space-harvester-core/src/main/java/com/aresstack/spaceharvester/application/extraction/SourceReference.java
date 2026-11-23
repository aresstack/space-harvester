package com.aresstack.spaceharvester.application.extraction;

/**
 * Wo eine Information im Originaldokument steht. Alle Angaben sind freiwillig; {@link #NONE} hat keine.
 *
 * @param section   Abschnitt oder Überschrift, leer wenn unbekannt
 * @param page      Seite ab 1, {@code 0} unbekannt
 * @param paragraph Absatz ab 1, {@code 0} unbekannt
 * @param start     Anfang des Textbereichs im extrahierten Dokumenttext (Zeichen ab 0), {@code -1} unbekannt
 * @param end       Ende (exklusiv), {@code -1} unbekannt
 * @param excerpt   wörtlicher Textauszug, leer wenn keiner
 */
public record SourceReference(String section, int page, int paragraph, int start, int end, String excerpt) {

    public static final SourceReference NONE = new SourceReference("", 0, 0, -1, -1, "");

    public SourceReference {
        section = section == null ? "" : section.strip();
        excerpt = excerpt == null ? "" : excerpt.strip();
        page = Math.max(0, page);
        paragraph = Math.max(0, paragraph);
        if (start < 0 || end < start) {
            start = -1;
            end = -1;
        }
    }

    /** Fundstelle ohne Zeichenbereich. */
    public SourceReference(String section, int page, int paragraph, String excerpt) {
        this(section, page, paragraph, -1, -1, excerpt);
    }

    /** Ob ein Zeichenbereich bekannt ist (Sprung zur Fundstelle). */
    public boolean hasRange() {
        return start >= 0;
    }

    /** Ob gar keine Angabe vorliegt. */
    public boolean isEmpty() {
        return section.isEmpty() && page == 0 && paragraph == 0 && !hasRange() && excerpt.isEmpty();
    }
}
