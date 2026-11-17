package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stand einer Seite (ADR 0032, FL6). Das Ergebnis sind die Zahlen je Stand; das eine Etikett {@link #standing()} steht
 * nur daneben.
 *
 * @param page          Seite bzw. Dokument
 * @param counts        Anzahl aller Aussagen der Sicht je Stand, auch unsichere (alle Stände, auch 0)
 * @param certainCounts Anzahl der sicheren Aussagen je Stand; nur sie zählen für das Etikett
 * @param standing      Etikett
 * @param basis         {@link StandingBasis#DECISION}, {@link StandingBasis#PAGE} (Labels, Titel, Lage) oder
 *                      {@link StandingBasis#STATEMENTS}
 * @param reason        fester Grund
 * @param marks         alle gefundenen Hinweise aus Labels und Titeln (auch, wenn ein Mensch entschieden hat); der
 *                      erste passende hat gegriffen
 * @param decision      Entscheidung eines Menschen zur Seite oder {@code null}
 */
public record PageStanding(KnowledgeGraph.PageRef page, Map<Standing, Integer> counts,
                           Map<Standing, Integer> certainCounts, Standing standing, StandingBasis basis,
                           StandingReason reason, List<PageMark> marks, StandingDecision decision) {

    public PageStanding {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(reason, "reason");
        counts = complete(counts);
        certainCounts = complete(certainCounts);
        marks = List.copyOf(marks);
    }

    static Map<Standing, Integer> complete(Map<Standing, Integer> counts) {
        EnumMap<Standing, Integer> all = new EnumMap<>(Standing.class);
        for (Standing standing : Standing.values()) {
            all.put(standing, counts == null ? 0 : counts.getOrDefault(standing, 0));
        }
        return Collections.unmodifiableMap(all);
    }
}
