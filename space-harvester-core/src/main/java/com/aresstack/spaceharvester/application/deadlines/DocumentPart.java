package com.aresstack.spaceharvester.application.deadlines;

import java.util.Objects;

/**
 * Ein Sachverhalt als Bereich im Originaltext (ADR 0035, Abschnitte 3 und 4): Die KI-Stufe fragt je Teil, und die
 * Validierung sucht ein Zitat zuerst in diesem Bereich. Die Trennung selbst macht die Regex-Stufe; ohne Trennung ist
 * das ganze Dokument ein Teil.
 *
 * @param matterId Sachverhalt wie in {@code AnalysisSection#matterId}; leer für das ganze Dokument
 * @param matter   Anzeigename des Sachverhalts
 * @param start    erstes Zeichen im Text
 * @param end      Position danach
 */
public record DocumentPart(String matterId, String matter, int start, int end) {

    public DocumentPart {
        matterId = matterId == null ? "" : matterId;
        matter = matter == null ? "" : matter;
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Ungültiger Bereich " + start + "-" + end);
        }
    }

    /** Das ganze Dokument als ein Teil ohne Sachverhalt. */
    public static DocumentPart whole(String text) {
        return new DocumentPart("", "", 0, Objects.requireNonNull(text, "text").length());
    }

    /** Der Text dieses Teils. */
    public String of(String text) {
        return text.substring(Math.min(start, text.length()), Math.min(end, text.length()));
    }
}
