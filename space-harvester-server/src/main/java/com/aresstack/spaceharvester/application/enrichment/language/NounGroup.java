package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.List;

/**
 * Eine Nominalgruppe eines Satzes: optional ein Artikel, attributive Adjektive und ein Begriff aus einem oder mehreren
 * aufeinanderfolgenden Nomen, Akronymen oder Kennungen ({@code die zuständige Sachbearbeitung}, {@code Confluence Data
 * Center}). Ein Vorkommen, kein Konzept: zwei Gruppen mit gleichem Begriff bleiben zwei Gruppen.
 *
 * @param from     Index des ersten Tokens im Satz (Artikel oder Adjektiv, sonst erstes Nomen)
 * @param termFrom Index des ersten Nomens
 * @param to       Index nach dem letzten Nomen
 * @param term     Begriff im Wortlaut der Seite
 * @param kind     {@code noun}, {@code acronym} oder {@code identifier}
 */
record NounGroup(int from, int termFrom, int to, String term, String kind) {

    /** Normalisierter Begriff: Unicode NFC, Kleinbuchstaben, Leerraum zusammengefasst. Nie eine Identität. */
    String normalized() {
        return Terms.normalize(term);
    }

    List<Token> adjectives(Sentence sentence) {
        int first = from < termFrom && sentence.tokens().get(from).is(WordClass.DETERMINER) ? from + 1 : from;
        return sentence.tokens().subList(first, termFrom);
    }

    int charStart(Sentence sentence) {
        return sentence.tokens().get(from).start();
    }

    int termStart(Sentence sentence) {
        return sentence.tokens().get(termFrom).start();
    }

    int charEnd(Sentence sentence) {
        return sentence.tokens().get(to - 1).end();
    }
}
