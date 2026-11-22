package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Status-quo-Sicht über eine Auswahl (ADR 0032, FL6): was fachlich gerade gilt, danach Geplantes, Unklares und
 * zuletzt Überholtes. Nichts wird versteckt. Reine Auswertung, speichert nichts.
 *
 * @param entries          alle Aussagen der Sicht, sortiert nach Stand ({@link Standing}), dann Seite, Satz, Stelle
 * @param states           Zustandsaussagen je Träger, Träger mit den meisten geltenden Zuständen zuerst
 * @param pages            Stand je Seite bzw. Dokument, in der Reihenfolge der Seiten
 * @param counts           Anzahl der Aussagen aus aktuellen Analysen je Stand (alle Stände, auch 0)
 * @param certainCounts    Anzahl der sicheren Aussagen aus aktuellen Analysen je Stand
 * @param staleDecisions    Entscheidungen zu Aussagen sichtbarer Seiten, die es in der aktuellen Analyse nicht mehr
 *                          gibt (Text geändert, Vorstufe entzogen): Sie greifen nicht mehr und warten darauf, neu
 *                          getroffen oder gelöscht zu werden. Stand oder Prüfung; nur Einträge für die Ablage des
 *                          Analysators, aus der die Seite gelesen wurde
 * @param staleEntries      Einträge aus veralteten Analysen ({@link StatusQuoEntry#analysisStale()}): Sie stehen in
 *                          {@code entries}, zählen aber weder in den Zahlen noch im Stand je Seite, in {@code states}
 *                          oder bei möglichen Widersprüchen
 * @param rejectedDecisions Entscheidungen zu Aussagen der aktuellen Analyse, die ein Mensch verworfen hat
 *                          ({@code REJECTED}, ADR 0032 W3): Die Aussage zählt nicht und fehlt in {@code entries}, die
 *                          Entscheidung bleibt hier erreichbar, damit die Prüfung zurückgenommen werden kann. Nie
 *                          veraltet, solange es die Aussage gibt
 * @param lockedPages       die Seiten der Sicht, die im Bestand gesperrt sind (ADR 0014): Ihre Analyse bleibt lesbar,
 *                          Stand und Prüfung nehmen sie nicht an ({@link LockedPageException}); die Oberfläche sperrt
 *                          ihre Schalter vorab
 */
public record StatusQuoView(List<StatusQuoEntry> entries, List<BearerStates> states, List<PageStanding> pages,
                            Map<Standing, Integer> counts, Map<Standing, Integer> certainCounts,
                            List<StandingDecision> staleDecisions, int staleEntries,
                            List<StandingDecision> rejectedDecisions, Set<PageKey> lockedPages) {

    /** Ohne gesperrte Seiten. */
    public StatusQuoView(List<StatusQuoEntry> entries, List<BearerStates> states, List<PageStanding> pages,
                         Map<Standing, Integer> counts, Map<Standing, Integer> certainCounts,
                         List<StandingDecision> staleDecisions, int staleEntries,
                         List<StandingDecision> rejectedDecisions) {
        this(entries, states, pages, counts, certainCounts, staleDecisions, staleEntries, rejectedDecisions, Set.of());
    }

    /** Ohne verworfene Aussagen. */
    public StatusQuoView(List<StatusQuoEntry> entries, List<BearerStates> states, List<PageStanding> pages,
                         Map<Standing, Integer> counts, Map<Standing, Integer> certainCounts,
                         List<StandingDecision> staleDecisions, int staleEntries) {
        this(entries, states, pages, counts, certainCounts, staleDecisions, staleEntries, List.of());
    }

    /** Mit der Zahl der veralteten Einträge aus {@code entries}. */
    public StatusQuoView(List<StatusQuoEntry> entries, List<BearerStates> states, List<PageStanding> pages,
                         Map<Standing, Integer> counts, Map<Standing, Integer> certainCounts,
                         List<StandingDecision> staleDecisions) {
        this(entries, states, pages, counts, certainCounts, staleDecisions,
                (int) entries.stream().filter(StatusQuoEntry::analysisStale).count());
    }

    public StatusQuoView {
        entries = List.copyOf(entries);
        states = List.copyOf(states);
        pages = List.copyOf(pages);
        counts = PageStanding.complete(counts);
        certainCounts = PageStanding.complete(certainCounts);
        staleDecisions = List.copyOf(staleDecisions);
        rejectedDecisions = List.copyOf(rejectedDecisions);
        lockedPages = Set.copyOf(lockedPages);
    }

    /** Ob die Seite im Bestand gesperrt ist (kein Stand, keine Prüfung). */
    public boolean locked(PageKey page) {
        return lockedPages.contains(page);
    }
}
