package com.aresstack.spaceharvester.application.extraction;

import java.util.List;
import java.util.Objects;

/**
 * Ein fachlicher Abschnitt mit seinen Feldern in Lieferreihenfolge.
 *
 * @param matter Sachverhalt, zu dem der Abschnitt gehört, etwa „Konto 1“; leer, wenn das Dokument nur einen hat.
 *               Felder verschiedener Sachverhalte werden nie zusammengeführt oder kombiniert
 * @param title  Überschrift, etwa „Fristeninformationen“
 * @param fields Felder, auch mehrere gleichen Schlüssels
 */
public record AnalysisSection(String matter, String title, List<ExtractedField> fields) {

    public AnalysisSection {
        Objects.requireNonNull(title, "title");
        matter = matter == null ? "" : matter;
        fields = List.copyOf(fields);
    }

    public AnalysisSection(String title, List<ExtractedField> fields) {
        this("", title, fields);
    }

    /** Überschrift samt Sachverhalt, wie sie angezeigt wird. */
    public String heading() {
        return matter.isEmpty() ? title : matter + " – " + title;
    }
}
