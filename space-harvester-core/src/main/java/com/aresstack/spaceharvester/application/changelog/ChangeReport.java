package com.aresstack.spaceharvester.application.changelog;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Änderungsbericht eines Updates (ADR 0030, Abschnitt 4). Bei der Erstbefüllung nur Zähler, keine Seitenliste.
 *
 * @param pages die geänderten Seiten, die der Aufrufer sehen darf, nach Art und Titel
 */
public record ChangeReport(ChangeUpdate update, List<ReportedPage> pages) {

    public ChangeReport {
        pages = List.copyOf(pages);
    }

    /** Die Seiten nach Art, in der Reihenfolge der Arten. */
    public Map<ChangeKind, List<ReportedPage>> byKind() {
        Map<ChangeKind, List<ReportedPage>> grouped = new EnumMap<>(ChangeKind.class);
        for (ReportedPage page : pages) {
            grouped.computeIfAbsent(page.kind(), ignored -> new java.util.ArrayList<>()).add(page);
        }
        return grouped;
    }
}
