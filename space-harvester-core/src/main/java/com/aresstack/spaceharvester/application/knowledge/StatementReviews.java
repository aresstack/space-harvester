package com.aresstack.spaceharvester.application.knowledge;

import java.util.function.UnaryOperator;

/**
 * Port für die Prüfung einzelner Aussagen durch Menschen (ADR 0032, Umsetzung W3). Die Prüfung liegt nicht in der
 * Analyse, sondern in der Ablage der Entscheidungen ({@code application.standing}); dieser Port legt sie beim Lesen
 * über die gespeicherte Analyse, damit Zählung ({@link CountedStatements}), Herkunftsanzeige und Export dieselbe
 * wirksame Prüfung sehen.
 *
 * <p>Wirksam ist die Prüfung aus der Entscheidung; ohne sie bleibt die gespeicherte {@link ParsedStatements.Review}. Es
 * zählen nur Entscheidungen für die Ablage des Analysators, aus der die Analyse stammt
 * ({@link AnalyzerChoice#of(String)} ihrer Kennung): IDs von MaltParser und KI-Analysator können gleich lauten.
 *
 * <p>Nur auf Lesewegen anwenden ({@link KnowledgeGraph}, {@link StatementAnalysis#read}), nie vor dem Zurückschreiben in
 * die Ablage der Analysen: sonst stünde die Prüfung in der Analysedatei und ließe sich nicht mehr zurücknehmen.
 */
@FunctionalInterface
public interface StatementReviews {

    /** Keine Prüfungen durch Menschen: jede Analyse bleibt, wie sie gespeichert ist. */
    StatementReviews NONE = UnaryOperator::identity;

    /**
     * Ein Stand der Prüfungen für einen Lesevorgang (eine Momentaufnahme, nicht je Analyse neu gelesen).
     *
     * @return wendet die Prüfungen auf eine gespeicherte Analyse an; gibt dieselbe Instanz zurück, wenn keine passt
     */
    UnaryOperator<PageStatementAnalysis> snapshot();
}
