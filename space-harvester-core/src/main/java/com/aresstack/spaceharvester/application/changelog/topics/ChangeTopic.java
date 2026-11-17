package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.changelog.topics.ChangeUnit.Nature;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ein Themenvorschlag (ADR 0030, Nachtrag Abschnitt 4): Änderungseinheiten, die inhaltlich zusammengehören, über Tage,
 * Seiten und Personen hinweg. Kein KI-Text; Titel und Text bildet TZ4.
 *
 * @param id                stabil: die kleinste Kennung der Mitglieder
 * @param unitIds           die Mitglieder in der Reihenfolge der Einheiten
 * @param representativeIds die typischsten Mitglieder, das typischste zuerst (Schwerpunkt der Vektoren, ohne Vektoren
 *                          die größten)
 * @param label             deterministische Bezeichnung: häufigste Überschrift, sonst häufigster Begriff, sonst Titel
 *                          der ersten Seite; kein KI-Text
 * @param weight            Gewicht aus Textmenge, Fundstellen und Art; Themen stehen danach absteigend
 * @param spaces            beteiligte Spaces
 * @param pages             beteiligte Seiten in der Reihenfolge der Fundstellen
 * @param from              früheste Änderung
 * @param to                späteste Änderung
 * @param editors           Zahl der Bearbeiter; 0, solange der Unterschied keine trägt
 * @param signals           tragende Signale: mittlere Ähnlichkeit je Signal zwischen den Bündeln des Themas
 * @param natures           Einheiten je Art
 * @param statements        Aussagen-Unterschiede der Mitglieder; leer ohne Satzanalysator
 */
public record ChangeTopic(String id, List<String> unitIds, List<String> representativeIds, String label,
                          double weight, Set<String> spaces, List<PageKey> pages, Instant from, Instant to,
                          int editors, Map<Signal, Double> signals, Map<Nature, Integer> natures,
                          List<StatementDelta> statements) {

    public ChangeTopic {
        Objects.requireNonNull(id, "id");
        unitIds = List.copyOf(unitIds);
        representativeIds = List.copyOf(representativeIds);
        spaces = Set.copyOf(spaces);
        pages = List.copyOf(pages);
        signals = Map.copyOf(signals);
        natures = Map.copyOf(natures);
        statements = List.copyOf(statements);
    }
}
