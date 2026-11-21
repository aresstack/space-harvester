package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * @param spaceNames        Anzeigenamen der Spaces im Bericht, soweit bekannt; Schlüssel ist der Space-Schlüssel
 */
public record UpdateSummary(String sourceId, String updateId, String spaceKey, String text,
                            List<SummaryStatement> statements, String model, Instant createdAt, int unreadableChanges,
                            boolean stale, Map<String, String> spaceNames) {

    public UpdateSummary {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        Objects.requireNonNull(text, "text");
        statements = List.copyOf(statements);
        Objects.requireNonNull(createdAt, "createdAt");
        spaceNames = spaceNames == null ? Map.of() : Map.copyOf(spaceNames);
    }

    /** Ohne Namen der Spaces. */
    public UpdateSummary(String sourceId, String updateId, String spaceKey, String text,
                         List<SummaryStatement> statements, String model, Instant createdAt, int unreadableChanges,
                         boolean stale) {
        this(sourceId, updateId, spaceKey, text, statements, model, createdAt, unreadableChanges, stale, Map.of());
    }

    /** Anzeigename eines Space, sonst sein Schlüssel. */
    public String spaceName(String key) {
        String name = key == null ? null : spaceNames.get(key);
        return name == null || name.isBlank() ? key : name;
    }

    /** Zusammenfassung der ganzen Quelle (ohne Space). */
    public UpdateSummary(String sourceId, String updateId, String text, List<SummaryStatement> statements,
                         String model, Instant createdAt, int unreadableChanges, boolean stale) {
        this(sourceId, updateId, null, text, statements, model, createdAt, unreadableChanges, stale);
    }
}
