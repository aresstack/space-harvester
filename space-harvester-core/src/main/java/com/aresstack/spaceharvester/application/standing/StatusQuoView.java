package com.aresstack.spaceharvester.application.standing;

import java.util.List;
import java.util.Map;

/**
 * Die Status-quo-Sicht über eine Auswahl (ADR 0032, FL6): was fachlich gerade gilt, danach Geplantes, Unklares und
 * zuletzt Überholtes. Nichts wird versteckt. Reine Auswertung, speichert nichts.
 *
 * @param entries          alle Aussagen der Sicht, sortiert nach Stand ({@link Standing}), dann Seite, Satz, Stelle
 * @param states           Zustandsaussagen je Träger, Träger mit den meisten geltenden Zuständen zuerst
 * @param pages            Stand je Seite bzw. Dokument, in der Reihenfolge der Seiten
 * @param counts           Anzahl aller Aussagen je Stand (alle Stände, auch 0)
 * @param certainCounts    Anzahl der sicheren Aussagen je Stand
 * @param staleDecisions   Entscheidungen zu Aussagen sichtbarer Seiten, die es in der aktuellen Analyse nicht mehr
 *                         gibt (Text geändert, Vorstufe entzogen): Sie greifen nicht mehr und warten darauf, neu
 *                         getroffen oder gelöscht zu werden
 */
public record StatusQuoView(List<StatusQuoEntry> entries, List<BearerStates> states, List<PageStanding> pages,
                            Map<Standing, Integer> counts, Map<Standing, Integer> certainCounts,
                            List<StandingDecision> staleDecisions) {

    public StatusQuoView {
        entries = List.copyOf(entries);
        states = List.copyOf(states);
        pages = List.copyOf(pages);
        counts = PageStanding.complete(counts);
        certainCounts = PageStanding.complete(certainCounts);
        staleDecisions = List.copyOf(staleDecisions);
    }
}
