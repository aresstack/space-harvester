package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.Objects;

/** Eine Aussage der Zusammenfassung mit den Seiten, auf die sie sich stützt (ADR 0030, Abschnitt 5). */
public record SummaryStatement(String text, List<SummaryPage> pages) {

    public SummaryStatement {
        Objects.requireNonNull(text, "text");
        pages = List.copyOf(pages);
    }
}
