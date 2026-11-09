package com.aresstack.spaceharvester.application.knowledge;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Port des lokalen Satzanalysators (#499): macht aus dem Text einer Seite Erwähnungen, Aussagen und nominale Bezüge.
 * Die Desktop-App belegt ihn mit MaltParser aus {@code :nlp-parser}; Core und Dienst haben keine Umsetzung. Rechnet
 * lokal, ohne Netz und ohne KI-Dienst. Muss threadsicher sein.
 */
public interface StatementAnalyzer {

    /** Stabile Kennung des Verfahrens, z. B. {@code maltparser-de}. */
    String id();

    /**
     * Version aus Parser, Modell (samt Prüfsumme) und Regeln, z. B. {@code 1|maltparser-de-gsd-v1.0.0|237885c2}.
     * Ändert sie sich, gelten gespeicherte Analysen als veraltet ({@link PageStatements.State#STALE}). Darf das Modell
     * nicht laden.
     */
    String version();

    /**
     * Analysiert den Text. Lädt das Modell beim ersten Aufruf.
     *
     * @param documentId Präfix der IDs ({@code quelle/seite@fassung}); jede Erwähnung und Aussage beginnt damit
     * @param text       genau die Textprojektion, die der Leser zeigt; Spannen beziehen sich darauf
     * @param cancelled  wird zwischen den Sätzen gefragt; {@code true} bricht ab
     * @throws AnalyzerUnavailableException wenn Modell oder Laufzeit fehlen oder defekt sind
     * @throws CancellationException        nach einem Abbruch
     */
    ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled);
}
