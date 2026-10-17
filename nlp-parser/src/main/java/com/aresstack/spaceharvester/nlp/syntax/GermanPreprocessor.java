package com.aresstack.spaceharvester.nlp.syntax;

import java.util.List;

/**
 * Port: zerlegt deutschen Text in Sätze und syntaktische Wörter und bestimmt Wortarten und, soweit möglich,
 * Grundformen und Merkmale, also das Eingabeschema des Parsers. Implementierungen sind threadsicher.
 */
public interface GermanPreprocessor {

    /** Sätze mit Wörtern, Wortarten und Spannen in {@code text}; ohne Kopf und Relation. */
    List<Sentence> analyze(String text);

    /** Werkzeuge und Modelle je gelieferter Schicht. */
    List<Component> components();
}
