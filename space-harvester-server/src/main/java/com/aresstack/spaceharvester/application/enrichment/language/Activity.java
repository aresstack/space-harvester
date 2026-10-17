package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.List;

/**
 * Eine Handlung in einem Teilsatz: das Prädikat im Wortlaut, eine vorgeschlagene Grundform, Diathese, Modus und die
 * Beteiligten mit ihrer semantischen Rolle. Gilt nur für ihren Satz.
 *
 * @param sentence     Satz der Handlung
 * @param from         erstes Token des Teilsatzes
 * @param to           Index nach dem letzten Token des Teilsatzes
 * @param predicate    Prädikatsteile in Textreihenfolge (Hilfs- oder Modalverb, Vollverb, abgetrennte Partikel)
 * @param lemma        vorgeschlagene Grundform des Vollverbs, ungeprüft
 * @param passive      werden-Passiv: das grammatische Subjekt ist das betroffene Objekt
 * @param instruction  Anweisung (Infinitiv am Ende oder Imperativ mit {@code Sie}), ohne Subjekt
 * @param modal        Modalverb oder {@code null}
 * @param negated      der Teilsatz enthält eine Verneinung
 * @param participants Beteiligte in Textreihenfolge
 */
record Activity(Sentence sentence, int from, int to, List<Token> predicate, String lemma, boolean passive,
                boolean instruction, String modal, boolean negated, List<Participant> participants) {

    Activity {
        predicate = List.copyOf(predicate);
        participants = List.copyOf(participants);
    }

    /** Das Prädikat im Wortlaut, Teile mit {@code " … "} verbunden. */
    String predicateText() {
        return String.join(" … ", predicate.stream().map(Token::text).toList());
    }

    int charStart() {
        return sentence.tokens().get(from).start();
    }

    int charEnd() {
        return sentence.tokens().get(to - 1).end();
    }

    /** Der erste Beteiligte mit {@code role} oder {@code null}. */
    Participant first(Role role) {
        for (Participant participant : participants) {
            if (participant.role() == role) {
                return participant;
            }
        }
        return null;
    }

    /** Semantische Rolle, nicht grammatische Funktion: im Passiv ist das Subjekt das betroffene Objekt. */
    enum Role {
        /** Wer handelt (im Passiv die von- oder durch-Gruppe). */
        ACTOR,
        /** Worauf die Handlung wirkt. */
        AFFECTED_OBJECT,
        /** Wer etwas erhält (Dativ ohne Präposition). */
        RECIPIENT,
        /** Womit gehandelt wird (mit, mittels, per, über). */
        INSTRUMENT
    }

    /**
     * Ein Beteiligter.
     *
     * @param role    semantische Rolle
     * @param group   Nominalgruppe oder {@code null} bei einem Pronomen
     * @param pronoun Pronomen im Wortlaut oder {@code null}; sein Bezug wird nie aufgelöst
     */
    record Participant(Role role, NounGroup group, Token pronoun) {

        String term() {
            return group != null ? group.term() : pronoun.text();
        }

        String normalized() {
            return group != null ? group.normalized() : Terms.normalize(pronoun.text());
        }
    }
}
