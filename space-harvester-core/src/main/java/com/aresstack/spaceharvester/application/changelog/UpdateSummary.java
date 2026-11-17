package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Die Zusammenfassung eines Updates einer Quelle (ADR 0030, Abschnitt 5), wie der Aufrufer sie sehen darf.
 *
 * @param updateId          {@link ChangeUpdate#id()}, auch {@link ChangeUpdate#OPEN}
 * @param spaceKey          der Space der Ebene (ADR 0031), {@code null} für die ganze Quelle
 * @param text              der ganze Text
 * @param statements        die Aussagen mit ihren Seiten
 * @param model             Kennung des Modells
 * @param createdAt         Zeitpunkt der Erstellung
 * @param unreadableChanges geänderte Seiten, die das Modell nicht lesen durfte (ohne KI-Freigabe, ADR 0028)
 * @param stale             der Bericht hat sich seither geändert (nur beim offenen Update möglich); neu erstellen
 */
public record UpdateSummary(String sourceId, String updateId, String spaceKey, String text,
                            List<SummaryStatement> statements, String model, Instant createdAt, int unreadableChanges,
                            boolean stale) {

    public UpdateSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        Objects.requireNonNull(text, "text");
        statements = List.copyOf(statements);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Zusammenfassung der ganzen Quelle (ohne Space). */
    public UpdateSummary(String sourceId, String updateId, String text, List<SummaryStatement> statements,
                         String model, Instant createdAt, int unreadableChanges, boolean stale) {
        this(sourceId, updateId, null, text, statements, model, createdAt, unreadableChanges, stale);
    }
}
