package com.aresstack.spaceharvester.application.convert;

import java.util.Objects;

/**
 * Ein Hinweis eines Converters zur erzeugten Datei, etwa ein Bild, das im Datenbestand fehlt. Das Dokument nennt die
 * Stelle selbst; der Hinweis macht sie im Ergebnis der Konvertierung erkennbar, ohne die Datei zu öffnen.
 *
 * @param pageId    Seite, deren Kapitel betroffen ist
 * @param kind      Art des Hinweises
 * @param reference was fehlt, etwa Anhangstitel, Bildquelle oder {@code mermaid}; ohne Zugangsdaten
 * @param reason    kurzer Grund, etwa der Status aus {@code assets.json} ({@code missing}, {@code forbidden},
 *                  {@code too-large}, {@code unavailable}), {@code not-collected} oder {@code render-failed};
 *                  {@code null} ohne Angabe
 */
public record ConversionNotice(String pageId, Kind kind, String reference, String reason) {

    public ConversionNotice {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
    }

    /** Art des Hinweises. */
    public enum Kind {
        /** Ein Bild fehlt im Datenbestand oder ließ sich nicht einbetten. */
        MISSING_IMAGE,
        /** Ein Diagramm (draw.io, Mermaid) fehlt oder ließ sich nicht darstellen. */
        MISSING_DIAGRAM,
        /** Ein Anhang, auf den die Seite verweist, fehlt im Datenbestand. */
        MISSING_ATTACHMENT,
        /** Inhalt, den das Zielformat nicht darstellen kann und der deshalb fehlt oder vereinfacht ist. */
        OMITTED_CONTENT
    }
}
