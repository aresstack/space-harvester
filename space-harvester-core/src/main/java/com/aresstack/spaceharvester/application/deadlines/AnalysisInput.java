package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.extraction.DocumentRef;

import java.util.Objects;

/**
 * Eingang der Fristenanalyse (ADR 0035, Abschnitt 2): ein bereits gelesener Dokumenttext. Den Text liefert die
 * vorhandene Extraktion ({@code DocumentText}); hier wird nichts gelesen.
 *
 * @param document  Dokument samt Version (bei der E-Akte href und ETag); andere Version = anderes Dokument
 * @param title     Titel oder Dateiname, nur zur Anzeige
 * @param mediaType Medientyp, leer wenn unbekannt
 * @param text      Originaltext; alle Fundstellen zeigen in genau diesen Text
 * @param truncated ob die Extraktion den Text abgeschnitten hat
 */
public record AnalysisInput(DocumentRef document, String title, String mediaType, String text, boolean truncated) {

    public AnalysisInput {
        Objects.requireNonNull(document, "document");
        title = title == null ? "" : title;
        mediaType = mediaType == null ? "" : mediaType;
        text = text == null ? "" : text;
    }

    @Override
    public String toString() {
        // Ohne Text: toString landet leicht im Log.
        return "AnalysisInput[" + document + ", " + text.length() + " Zeichen, truncated=" + truncated + "]";
    }
}
