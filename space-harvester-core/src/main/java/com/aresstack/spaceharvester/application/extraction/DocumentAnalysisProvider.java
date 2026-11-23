package com.aresstack.spaceharvester.application.extraction;

import com.aresstack.spaceharvester.application.observe.Subscription;

import java.util.function.Consumer;

/**
 * Liefert Analyseergebnisse zu Dokumenten. Die einzige Stelle, an der später regel- oder KI-basierte Extraktion
 * angeschlossen wird; die Oberfläche bleibt dabei unverändert. Keine Methode wird auf dem Swing-Thread gerufen.
 *
 * <p>Analysieren ist ein Auftrag (ADR 0027, kein Polling): {@link #startAnalysis} stößt ihn nur an. Ändert sich das
 * Ergebnis eines Dokumentstands (Start, Teilergebnis, Ende, Fehler), meldet der Anbieter das über {@link #observe};
 * der Empfänger liest es dann mit {@link #result}.
 */
public interface DocumentAnalysisProvider {

    /** Ohne angeschlossene Extraktion: jedes Dokument ist „noch nicht analysiert“, Analysieren geht nicht. */
    DocumentAnalysisProvider NONE = document -> DocumentAnalysis.notAnalyzed(document);

    /**
     * Das vorliegende Ergebnis zum Dokumentstand, auch während einer laufenden Analyse
     * ({@link AnalysisState#IN_PROGRESS}); nie {@code null}, ohne Ergebnis {@link AnalysisState#NOT_ANALYZED}. Darf
     * blockieren.
     */
    DocumentAnalysis result(DocumentRef document) throws Exception;

    /** Ob {@link #startAnalysis} für diesen Stand möglich ist. Ohne echten Analysedienst {@code false}. */
    default boolean canAnalyze(DocumentRef document) {
        return false;
    }

    /**
     * Stößt die Analyse des Dokumentstands als Auftrag an und kehrt zurück; das Ergebnis kommt über {@link #observe}.
     * Liest das Dokument nur, ändert es nie.
     *
     * @throws UnsupportedOperationException wenn {@link #canAnalyze} {@code false} ist
     */
    default void startAnalysis(DocumentRef document) throws Exception {
        throw new UnsupportedOperationException("Keine Dokumentanalyse angeschlossen");
    }

    /**
     * Meldet jede Änderung eines Ergebnisses mit dem betroffenen Dokumentstand, auf einem beliebigen Thread. Ohne
     * Analysedienst ändert sich nie etwas.
     */
    default Subscription observe(Consumer<DocumentRef> changed) {
        return Subscription.NONE;
    }
}
