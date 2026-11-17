package com.aresstack.spaceharvester.application.changelog;

import java.util.List;

/**
 * Unterschied zweier Lesetexte nach Absätzen (ADR 0030, Abschnitt 4).
 *
 * @param paragraphs die geänderten Absätze in Textreihenfolge; leer, wenn sich nur Unsichtbares geändert hat
 * @param truncated  ob Absätze wegen der Längengrenze fehlen
 */
public record TextDiff(List<ParagraphChange> paragraphs, boolean truncated) {

    public TextDiff {
        paragraphs = List.copyOf(paragraphs);
    }
}
