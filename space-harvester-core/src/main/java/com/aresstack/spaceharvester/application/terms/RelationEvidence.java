package com.aresstack.spaceharvester.application.terms;

import java.util.Objects;

/**
 * Beleg einer Beziehung: eine Fundstelle aus dem Begriffsindex (#119). Die Beziehungsablage erfindet keine Positionen;
 * ein Beleg verweist nur auf eine Stelle, die ein Vorschlagslauf oder Bearbeiter dort gesehen hat.
 *
 * @param sourceId    Quelle
 * @param pageId      Seite
 * @param start       Zeichenposition im projizierten Text
 * @param end         Position nach der Stelle
 * @param inputSha256 Eingabe-Hash des Textes, auf den sich die Positionen beziehen
 */
public record RelationEvidence(String sourceId, String pageId, int start, int end, String inputSha256) {

    public RelationEvidence {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(inputSha256, "inputSha256");
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Ungültiger Beleg");
        }
    }
}
