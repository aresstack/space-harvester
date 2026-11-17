package com.aresstack.spaceharvester.application.changelog.topics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Die Themenvorschläge eines Updates auf einer Ebene (ADR 0030, Nachtrag Abschnitt 4): Quelle oder ein Space.
 *
 * @param method           Verfahren und Version, {@value TopicProposer#METHOD}
 * @param inputSha256      Hash über Einheiten, Verfahren, Gewichte und verfügbare Signale; ändert er sich, ist eine
 *                         darauf gebaute Zusammenfassung veraltet
 * @param signals          die Signale, die in die Gruppierung eingingen
 * @param missingSignals   Signale mit Gewicht, die fehlten oder nur eingeschränkt vorlagen (z. B. {@code VECTOR}
 *                         ohne Embedding-Dienst, {@code STATEMENTS} ohne Satzanalysator, {@code SECTION} ohne
 *                         Überschriftenpfad); Ergebnis und Oberfläche nennen sie
 * @param topics           die Themen nach Gewicht absteigend, Gleichstände nach Kennung
 * @param units            alle Einheiten
 * @param corrections      kleine Korrekturen, nur gezählt, in keinem Thema
 * @param notEvaluated     geänderte Seiten ohne auswertbaren Unterschied
 * @param notReleased      Einheiten ohne KI-Freigabe; sie stehen in keinem Thema, nur gezählt
 * @param ungroupedUnitIds Einheiten jenseits der Obergrenze, nicht gruppiert
 */
public record TopicSet(String sourceId, String updateId, Optional<String> spaceKey, String method,
                       String inputSha256, Set<Signal> signals, Set<Signal> missingSignals, List<ChangeTopic> topics, List<ChangeUnit> units,
                       int corrections, int notEvaluated, int notReleased, List<String> ungroupedUnitIds) {

    public TopicSet {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(updateId, "updateId");
        spaceKey = spaceKey == null ? Optional.empty() : spaceKey;
        signals = Set.copyOf(signals);
        missingSignals = Set.copyOf(missingSignals);
        topics = List.copyOf(topics);
        units = List.copyOf(units);
        ungroupedUnitIds = List.copyOf(ungroupedUnitIds);
    }
}
