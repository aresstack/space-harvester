package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Schlägt Erwähnungen, Relationen und Handlungen für einen Absatz vor (#108). Austauschbar: der erste fragt das
 * Chat-Modell nach einer strengen JSON-Antwort; ein Dependency-Parser könnte denselben Vertrag erfüllen. Vorschläge
 * gelten nie als solche ({@link StatementValidator}).
 */
public interface StatementExtractor {

    /** Ob der Extraktor jetzt laufen kann (etwa: ein Chat-Modell ist eingestellt). */
    boolean isAvailable();

    /** Kennung von Extraktor, Prompt und Modell; Teil der Prozessorversion. */
    String version();

    /**
     * Blockiert (Modellaufruf).
     *
     * @throws StatementExtractionException wenn kein Vorschlag zu bekommen war
     */
    StatementCandidates extract(ParagraphText paragraph);
}
