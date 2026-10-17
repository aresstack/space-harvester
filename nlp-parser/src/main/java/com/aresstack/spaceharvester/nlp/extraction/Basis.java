package com.aresstack.spaceharvester.nlp.extraction;

/** Worauf eine Zuordnung beruht. Alles außer {@link #PARSER} ist eine Ableitung und nie eine sichere Parseraussage. */
public enum Basis {
    /** Direkt aus einer Kante des Dependency-Parsers. */
    PARSER,
    /** Subjekt vom koordinierten Prädikat übernommen ({@code prüft … und genehmigt}); der Parser sagt das nicht. */
    COORDINATION,
    /** Aus Kasusmerkmalen oder Artikelformen geschlossen ({@link CaseFallback}). */
    CASE_HEURISTIC,
    /** Aus einer festen Wortliste (Zeitangaben, Präpositionen für Mittel). */
    LEXICON
}
