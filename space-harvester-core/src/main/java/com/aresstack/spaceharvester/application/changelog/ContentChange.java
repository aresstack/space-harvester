package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Der lokal gebildete Unterschied, als der Inhalt einer Seite im Cache ersetzt wurde (ADR 0030, Abschnitt 4): vom
 * Lesetext des bisherigen Inhalts zum neuen. Ohne KI, im selben Commit wie die Zeilen des Protokolls.
 *
 * @param fromVersion Version des bisherigen Inhalts, {@code null} ohne Versionskennung
 * @param toVersion   Version des neuen Inhalts, {@code null} ohne Versionskennung
 * @param at          Zeitpunkt des Commits
 */
public record ContentChange(String sourceId, String pageId, String fromVersion, String toVersion, Instant at,
                            List<ParagraphChange> paragraphs, boolean truncated) {

    public ContentChange {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(at, "at");
        paragraphs = List.copyOf(paragraphs);
    }
}
