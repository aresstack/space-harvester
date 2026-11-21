package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Update-Marke einer Quelle (ADR 0030, Abschnitt 3): gesetzt, wenn keine manuelle Synchronisierung der Quelle mehr läuft
 * oder angehalten wartet. Ein Update der Quelle ist alles seit der vorigen Marke.
 *
 * @param sourceId     Quelle
 * @param at           Ende des letzten Auftrags der Runde
 * @param user         wer die Runde gestartet hat, {@code null} ohne Auftraggeber
 * @param spaces       die Spaces der Runde, sortiert
 * @param failedSyncs  wie viele Aufträge der Runde abgebrochen wurden oder scheiterten
 * @param initial      die erste Marke der Quelle: alles davor ist Erstbefüllung, keine Änderung
 * @param failedSpaces die Spaces der gescheiterten Aufträge, sortiert; leer bei Marken von vor dieser Angabe, dann
 *                     sind die gescheiterten Spaces unbekannt (Angelo, 21.11.2026: „Abgleich wiederholen“)
 */
public record UpdateMark(String sourceId, Instant at, String user, List<String> spaces, int failedSyncs,
                         boolean initial, List<String> failedSpaces) {

    public UpdateMark {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(at, "at");
        spaces = List.copyOf(spaces);
        failedSpaces = failedSpaces == null ? List.of() : List.copyOf(failedSpaces);
        if (failedSyncs < 0) {
            throw new IllegalArgumentException("failedSyncs < 0");
        }
    }

    /** Marke ohne bekannte gescheiterte Spaces. */
    public UpdateMark(String sourceId, Instant at, String user, List<String> spaces, int failedSyncs,
                      boolean initial) {
        this(sourceId, at, user, spaces, failedSyncs, initial, List.of());
    }

    /**
     * Die Spaces, die „Abgleich wiederholen“ erneut abgleicht: die gescheiterten, sind sie unbekannt, alle Spaces der
     * Runde; leer ohne gescheiterte Aufträge oder ohne Spaces (dann die ganze Quelle).
     */
    public List<String> retrySpaces() {
        if (failedSyncs == 0) {
            return List.of();
        }
        return failedSpaces.isEmpty() ? spaces : failedSpaces;
    }
}
