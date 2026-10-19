package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Die Aussagen einer Textprojektion (#108) und wie die Analyse lief: analysierte, gescheiterte und ausgelassene
 * Einheiten, verworfene Kandidaten nach Grund, Antworten der Ja/Nein-Prüfung. Die Diagnose enthält nie Text der Seite.
 *
 * @param mentions      Erwähnungen
 * @param events        Handlungen
 * @param relations     Eigenschaften und Folgen
 * @param analysedUnits Einheiten, für die der Extraktor antwortete
 * @param failedUnits   Einheiten ohne Antwort (Modell gescheitert oder unlesbar)
 * @param skippedUnits  Einheiten jenseits der Absatzgrenze
 * @param rejections    verworfene oder gekürzte Kandidaten nach Grund, auch von der Prüfung verneinte
 *                      ({@code check-denied})
 * @param verdicts      Antworten der Prüfung
 */
public record DocumentStatements(List<Mention> mentions, List<Event> events, List<Relation> relations,
                                 int analysedUnits, int failedUnits, int skippedUnits, Map<String, Integer> rejections,
                                 Map<StatementVerifier.Verdict, Integer> verdicts) {

    public DocumentStatements {
        mentions = List.copyOf(mentions);
        events = List.copyOf(events);
        relations = List.copyOf(relations);
        rejections = java.util.Collections.unmodifiableMap(new TreeMap<>(rejections));
        verdicts = java.util.Collections.unmodifiableMap(new TreeMap<>(verdicts));
    }

    @Override
    public String toString() {
        return "DocumentStatements[" + mentions.size() + " mentions, " + events.size() + " events, "
                + relations.size() + " relations; units " + analysedUnits + "/" + failedUnits + " failed/"
                + skippedUnits + " skipped; rejected " + rejections + "; checks " + verdicts + "]";
    }
}
