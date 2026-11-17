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
     * @param text       die Textprojektion, die der Leser zeigt, gleich lang und mit gleichen Spannen; jeder
     *                   Zeilenumbruch ist eine Blockgrenze (Überschrift, Absatz, Listenpunkt), weiche Umbrüche
     *                   innerhalb eines Absatzes sind schon Leerzeichen ({@link StatementAnalysis#analyzerText})
     * @param cancelled  wird zwischen den Sätzen gefragt; {@code true} bricht ab
     * @throws AnalyzerUnavailableException wenn Modell oder Laufzeit fehlen oder defekt sind
     * @throws CancellationException        nach einem Abbruch
     */
    ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled);

    /**
     * Wie {@link #analyze(String, String, BooleanSupplier)}, mit der Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3):
     * Der Analysator parst das Original, wählt nach {@link Simplification#mode()} Sätze und Abschnitte, gibt sie an
     * {@link Simplification#simplify}, parst nur die vereinfachten Sätze und führt je Originalsatz zusammen.
     * PLAUSIBLE-Aussagen des Originals bleiben immer; Merkmale kommen aus dem Original, sobald es eine Aussage zum
     * selben Verb hat; Aussagen der Vorstufe tragen {@link ParsedStatements.Derivation}. Ohne eigene Umsetzung ohne
     * Vorstufe.
     *
     * @param simplification Vorstufe des Core: Freigabe, Modell, Ablage; nie {@code null}
     */
    default ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled,
                                     Simplification simplification) {
        return analyze(documentId, text, cancelled);
    }

    /** Die Vorstufe, wie der Core sie dem Analysator gibt (ADR 0032, FL3). */
    interface Simplification {

        /** Welche Sätze vereinfacht werden. */
        com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode mode();

        /**
         * Vereinfacht die gewählten Sätze der Abschnitte: je Satzindex die vereinfachten Sätze, leer, wenn keiner
         * galt. Ein Abschnitt enthält wie in der Messung alle seine Zeilen; nur die gewählten werden vereinfacht.
         *
         * @throws java.util.concurrent.CancellationException nach einem Abbruch
         * @throws RuntimeException                           wenn das Modell scheitert oder die Freigabe fehlt
         */
        java.util.Map<Integer, java.util.List<com.aresstack.spaceharvester.application.simplification.SimplifiedSentence>>
        simplify(java.util.List<com.aresstack.spaceharvester.application.simplification.SimplificationSection> sections);
    }
}
