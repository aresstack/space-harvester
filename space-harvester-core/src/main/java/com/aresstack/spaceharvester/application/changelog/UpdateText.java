package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.Objects;

/**
 * Der Update-Text einer Ebene (ADR 0031, Abschnitt 2): die feste Textform eines Updates für den Reiter „Text“, zum
 * Kopieren und Exportieren und, nur mit KI-freigegebenen Seiten, als Material der Zusammenfassung. Eine Projektion des
 * Berichts, kein zweiter Bestand; seitenweise lesbar.
 *
 * @param format     {@value #FORMAT}
 * @param spaceKey   der Space der Ebene, {@code null} für die ganze Quelle
 * @param totalPages geänderte Seiten der Ebene insgesamt
 * @param offset     erste Seite dieses Ausschnitts
 * @param report     der Bericht mit Unterschieden, nur mit den Seiten dieses Ausschnitts
 * @param markdown   der Text dieses Ausschnitts
 * @param sections   Lage jeder Seite in {@code markdown}, in Textreihenfolge
 */
public record UpdateText(String format, String sourceId, String updateId, String spaceKey, int totalPages, int offset,
                         ChangeReport report, String markdown, List<Section> sections) {

    /** Kennung der Textform. */
    public static final String FORMAT = "update-text/v1";
    /** Höchstens so viele Seiten je Ausschnitt. */
    public static final int MAX_LIMIT = 200;

    /**
     * Lage einer Seite im Text.
     *
     * @param start  erstes Zeichen in {@code markdown}
     * @param length Zeichen bis zum Beginn der nächsten Seite
     * @param kind   die Art, unter der die Seite im Bericht steht
     */
    public record Section(String pageId, int start, int length, ChangeKind kind) {

        public Section {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public UpdateText {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(markdown, "markdown");
        sections = List.copyOf(sections);
    }

    /** Ob nach diesem Ausschnitt noch Seiten folgen. */
    public boolean more() {
        return offset + report.pages().size() < totalPages;
    }
}
