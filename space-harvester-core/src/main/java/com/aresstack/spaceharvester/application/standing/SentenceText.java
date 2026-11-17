package com.aresstack.spaceharvester.application.standing;

/**
 * Wortlaut einer Aussage für die Anzeige (ADR 0032, FL6): der Originalsatz aus dem Lesetext, wenn dieser noch genau der
 * analysierte Text ist, und der vereinfachte Satz der Vorstufe aus der Aussage selbst. Grenzen als Zeichen im Lesetext,
 * damit „Im Lesetext zeigen“ den ganzen Satz markiert.
 *
 * @param original       Originalsatz oder {@code null}, wenn der Lesetext nicht mehr passt oder fehlt
 * @param simplified     vereinfachter Satz ({@code Derivation#simplifiedSentence}) oder {@code null}
 * @param sentenceStart  Beginn des Satzes im Lesetext, -1 ohne Satzgrenzen
 * @param sentenceEnd    Ende des Satzes (exklusiv), -1 ohne Satzgrenzen
 * @param predicateStart Beginn des Prädikats im Lesetext
 * @param predicateEnd   Ende des Prädikats (exklusiv)
 * @param stale          ob der Lesetext nicht mehr zur Analyse passt (oder nicht vorliegt)
 */
public record SentenceText(String original, String simplified, int sentenceStart, int sentenceEnd,
                           int predicateStart, int predicateEnd, boolean stale) {
}
