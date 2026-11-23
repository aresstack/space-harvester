package com.aresstack.spaceharvester.application.extraction;

/**
 * Liefert Analyseergebnisse zu Dokumenten. Die einzige Stelle, an der später regel- oder KI-basierte Extraktion
 * angeschlossen wird; die Oberfläche bleibt dabei unverändert. Beide Methoden dürfen blockieren und werden nie auf dem
 * Swing-Thread gerufen.
 */
public interface DocumentAnalysisProvider {

    /** Ohne angeschlossene Extraktion: jedes Dokument ist „noch nicht analysiert“, Analysieren geht nicht. */
    DocumentAnalysisProvider NONE = document -> DocumentAnalysis.notAnalyzed(document);

    /** Das vorliegende Ergebnis zum Dokumentstand; nie {@code null}, ohne Ergebnis {@link AnalysisState#NOT_ANALYZED}. */
    DocumentAnalysis result(DocumentRef document) throws Exception;

    /** Ob {@link #analyze} für diesen Stand möglich ist. Ohne echten Analysedienst {@code false}. */
    default boolean canAnalyze(DocumentRef document) {
        return false;
    }

    /**
     * Analysiert den Dokumentstand und liefert das neue Ergebnis. Liest das Dokument nur, ändert es nie.
     *
     * @throws UnsupportedOperationException wenn {@link #canAnalyze} {@code false} ist
     */
    default DocumentAnalysis analyze(DocumentRef document) throws Exception {
        throw new UnsupportedOperationException("Keine Dokumentanalyse angeschlossen");
    }
}
