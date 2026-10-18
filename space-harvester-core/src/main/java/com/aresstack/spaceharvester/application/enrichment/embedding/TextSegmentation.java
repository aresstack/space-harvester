package com.aresstack.spaceharvester.application.enrichment.embedding;

import java.util.List;

/**
 * Satzgrenzen und Token-Budget für die Zerlegung im {@link EmbeddingProcessor} (#181). Umgesetzt mit den Bausteinen
 * des Enterprise AI Client in {@link com.aresstack.spaceharvester.source.KnowledgeTextSegmentation}.
 */
public interface TextSegmentation {

    /** Kennung von Budget, Überlappung und Zählweise; Teil der Prozessorversion. */
    String id();

    /** Höchstzahl Tokens je Stück. */
    int maxTokens();

    /** Wie viele Sätze das nächste Stück vom vorigen wiederholt. */
    int overlapSentences();

    /** Sätze eines Textblocks in ihrer Reihenfolge, jeweils wörtlich aus dem Block. */
    List<String> sentences(String block);

    int tokens(String text);
}
