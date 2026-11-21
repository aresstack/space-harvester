package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.Objects;

/**
 * Eine Aussage der Zusammenfassung mit den Seiten, auf die sie sich stützt (ADR 0030, Abschnitt 5). Seit
 * {@code update-summary-v2} ist jede Aussage ein Thema: Seiten, die inhaltlich zusammengehören, auch aus mehreren
 * Spaces, mit kurzem Text und einer ausführlicheren Fassung für das Detailfenster.
 *
 * @param title   kurzer Titel des Themas, {@code null} bei älteren Zusammenfassungen
 * @param details ausführlichere Zusammenfassung des Themas, {@code null} wenn keine vorliegt
 */
public record SummaryStatement(String text, List<SummaryPage> pages, String title, String details) {

    public SummaryStatement {
        Objects.requireNonNull(text, "text");
        pages = List.copyOf(pages);
    }

    /** Ohne Titel und Details. */
    public SummaryStatement(String text, List<SummaryPage> pages) {
        this(text, pages, null, null);
    }
}
