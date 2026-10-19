package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.ArrayList;
import java.util.List;

/**
 * Eine Handlung in einem Satz (#108): das verbale Prädikat (eine oder zwei Wortgruppen bei einem trennbaren Verb),
 * seine vorgeschlagene Grundform und sein Suchwert, die Beteiligten mit semantischer Rolle und der Kontext, in dem sie
 * gilt.
 *
 * @param key            Schlüssel innerhalb der Seite
 * @param sentence       Bereich des Satzes
 * @param predicate      die Prädikatswörter: das Verb, dann eine abgetrennte Partikel
 * @param lemmaCandidate vorgeschlagene Grundform oder {@code null}
 * @param normalized     Suchwert
 * @param normalization  Verfahren des Suchwerts
 * @param verbal         ob ein Wortartmodell ein Verb bestätigt hat, oder {@code null} ohne Modell
 * @param participants   die Beteiligten
 * @param context        Verneinung, Modalität, Bedingung
 * @param status         {@link ExtractionStatus}
 */
public record Event(String key, TextSpan sentence, List<GroundedText> predicate, String lemmaCandidate,
                    String normalized, String normalization, Boolean verbal, List<Participant> participants,
                    StatementContext context, ExtractionStatus status) {

    public Event {
        predicate = List.copyOf(predicate);
        participants = List.copyOf(participants);
        context = context == null ? StatementContext.none() : context;
    }

    /** Das Prädikat im Wortlaut, Teile verbunden mit „ … “. */
    public String predicateText() {
        StringBuilder text = new StringBuilder();
        for (GroundedText part : predicate) {
            if (!text.isEmpty()) {
                text.append(" … ");
            }
            text.append(part.text());
        }
        return text.toString();
    }

    /** Die Erwähnungen in {@code role}, in Reihenfolge. */
    public List<Mention> participants(ParticipantRole role) {
        List<Mention> mentions = new ArrayList<>();
        for (Participant participant : participants) {
            if (participant.role() == role) {
                mentions.add(participant.mention());
            }
        }
        return mentions;
    }

    /** Dieselbe Handlung mit anderem Status. */
    public Event withStatus(ExtractionStatus value) {
        return new Event(key, sentence, predicate, lemmaCandidate, normalized, normalization, verbal, participants,
                context, value);
    }

    @Override
    public String toString() {
        return "Event[" + key + " " + participants + ", " + status + "]";
    }
}
