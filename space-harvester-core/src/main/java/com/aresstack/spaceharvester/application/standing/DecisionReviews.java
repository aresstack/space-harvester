package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.StatementReviews;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Die eine Stelle, an der die Prüfung durch Menschen wirkt (ADR 0032, Umsetzung W3): legt die Prüfung aus den
 * Entscheidungen über die gespeicherte Analyse. Die Entscheidung gilt; ohne sie bleibt die gespeicherte Prüfung der
 * Aussage. Es passen nur Einträge mit demselben Analysator ({@link AnalyzerChoice#of(String)} der Analyse), derselben
 * Seite und derselben Aussage-ID.
 *
 * <p>Liest direkt die Ablage, nicht {@link StatusQuo}: Die Sicht liest selbst über den Knowledge Graph, der diese
 * Prüfungen schon trägt. Schreibt nie.
 */
public final class DecisionReviews implements StatementReviews {

    private final StandingDecisionStore store;

    public DecisionReviews(StandingDecisionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public UnaryOperator<PageStatementAnalysis> snapshot() {
        Map<StandingDecision.Target, ParsedStatements.Review> reviews = new HashMap<>();
        for (StandingDecision decision : store.all()) {
            if (decision.review() != null) {
                reviews.put(decision.target(), decision.review());
            }
        }
        return reviews.isEmpty() ? UnaryOperator.identity() : analysis -> apply(analysis, reviews);
    }

    private static PageStatementAnalysis apply(PageStatementAnalysis analysis,
                                               Map<StandingDecision.Target, ParsedStatements.Review> reviews) {
        AnalyzerChoice analyzer = AnalyzerChoice.of(analysis.analyzerId());
        ParsedStatements result = analysis.result();
        List<ParsedStatements.Statement> statements = new ArrayList<>(result.statements().size());
        boolean changed = false;
        for (ParsedStatements.Statement statement : result.statements()) {
            ParsedStatements.Review review = reviews.get(
                    new StandingDecision.Target(analysis.key(), statement.id(), analyzer));
            if (review != null && review != statement.review()) {
                statement = statement.withReview(review);
                changed = true;
            }
            statements.add(statement);
        }
        return changed ? analysis.withResult(new ParsedStatements(result.sentences(), result.mentions(), statements,
                result.attachments(), result.components(), result.conllu())) : analysis;
    }
}
