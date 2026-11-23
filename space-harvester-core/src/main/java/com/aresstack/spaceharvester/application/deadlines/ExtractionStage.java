package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.Processor;

/**
 * Eine Stufe der Faktenextraktion (ADR 0035, Abschnitt 3): Regex, KI oder Validierung. Jede Stufe liefert nur
 * Dokumentfakten im gemeinsamen Modell; zusammengeführt wird ausschließlich über {@code FieldMerge}. Eine Stufe wendet
 * nie Regeln an und ergänzt nichts, was nicht im Text steht.
 */
public interface ExtractionStage {

    /** Kennung und Version der Stufe; eine neue Version ersetzt die Felder der alten. */
    Processor processor();

    /** Ob die Stufe jetzt laufen kann (etwa: ein Modell ist eingestellt und das Dokument freigegeben). */
    default boolean available(AnalysisInput input) {
        return true;
    }

    /**
     * Blockiert; nie auf dem EDT.
     *
     * @param previous das bisherige Ergebnis früherer Stufen (für die Validierung), sonst
     *                 {@link DocumentAnalysis#notAnalyzed}
     * @return das Ergebnis dieser Stufe; jedes Feld trägt {@link #processor()}
     */
    DocumentAnalysis extract(AnalysisInput input, DocumentAnalysis previous);
}
