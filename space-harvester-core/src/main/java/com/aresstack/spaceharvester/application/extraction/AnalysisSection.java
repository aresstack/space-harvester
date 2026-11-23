package com.aresstack.spaceharvester.application.extraction;

import java.util.List;
import java.util.Objects;

/**
 * Ein fachlicher Abschnitt mit seinen Feldern in Lieferreihenfolge.
 *
 * @param matterId stabile Kennung des Sachverhalts, nach der zusammengeführt wird (etwa aus Bewirtschafternummer und
 *                 Objektkonto); leer, wenn das Dokument nur einen hat. Felder verschiedener Sachverhalte werden nie
 *                 zusammengeführt oder kombiniert
 * @param matter   Anzeigename des Sachverhalts, etwa „Konto 1“; leer ohne
 * @param title    Überschrift, etwa „Fristeninformationen“
 * @param fields   Felder, auch mehrere gleichen Schlüssels
 */
public record AnalysisSection(String matterId, String matter, String title, List<ExtractedField> fields) {

    public AnalysisSection {
        Objects.requireNonNull(title, "title");
        matterId = matterId == null ? "" : matterId;
        matter = matter == null ? "" : matter;
        fields = List.copyOf(fields);
    }

    /** Abschnitt ohne Sachverhalt. */
    public AnalysisSection(String title, List<ExtractedField> fields) {
        this("", "", title, fields);
    }

    /** Überschrift samt Sachverhalt, wie sie angezeigt wird. */
    public String heading() {
        return matter.isEmpty() ? title : matter + " – " + title;
    }
}
