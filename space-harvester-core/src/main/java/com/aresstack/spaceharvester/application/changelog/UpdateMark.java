package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Update-Marke einer Quelle (ADR 0030, Abschnitt 3): gesetzt, wenn keine manuelle Synchronisierung der Quelle mehr läuft
 * oder angehalten wartet. Ein Update der Quelle ist alles seit der vorigen Marke.
 *
 * @param sourceId    Quelle
 * @param at          Ende des letzten Auftrags der Runde
 * @param user        wer die Runde gestartet hat, {@code null} ohne Auftraggeber
 * @param spaces      die Spaces der Runde, sortiert
 * @param failedSyncs wie viele Aufträge der Runde abgebrochen wurden oder scheiterten
 * @param initial     die erste Marke der Quelle: alles davor ist Erstbefüllung, keine Änderung
 */
public record UpdateMark(String sourceId, Instant at, String user, List<String> spaces, int failedSyncs,
                         boolean initial) {

    public UpdateMark {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(at, "at");
        spaces = List.copyOf(spaces);
        if (failedSyncs < 0) {
            throw new IllegalArgumentException("failedSyncs < 0");
        }
    }
}
